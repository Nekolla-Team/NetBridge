use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

pub const REMEDIATION_MSG: &str = "\
The generated native ABI header is out of date.
Run: ./gradlew updateNativeHeader
or:  cd rust && cargo xtask abi-header update";

#[derive(Debug, PartialEq, Eq)]
pub enum CheckResult {
    Match,
    Mismatch,
    Missing,
}

/// Normalizes byte line endings to LF (`\n`) and ensures trailing newline.
pub fn normalize_line_endings(input: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(input.len());
    let mut i = 0;
    while i < input.len() {
        if input[i] == b'\r' {
            if i + 1 < input.len() && input[i + 1] == b'\n' {
                i += 1;
            }
            out.push(b'\n');
        } else {
            out.push(input[i]);
        }
        i += 1;
    }
    if !out.is_empty() && out.last() != Some(&b'\n') {
        out.push(b'\n');
    }
    out
}

/// Compares expected bytes with optional checked-in content.
pub fn check_bytes(expected: &[u8], actual: Option<&[u8]>) -> CheckResult {
    match actual {
        None => CheckResult::Missing,
        Some(actual_bytes) => {
            if actual_bytes == expected {
                CheckResult::Match
            } else {
                CheckResult::Mismatch
            }
        }
    }
}

/// Updates the header file only if contents differ. Returns true if modified.
pub fn update_header_file(header_path: &Path, expected_bytes: &[u8]) -> std::io::Result<bool> {
    if let Ok(existing_bytes) = fs::read(header_path)
        && existing_bytes == expected_bytes
    {
        return Ok(false);
    }
    if let Some(parent) = header_path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(header_path, expected_bytes)?;
    Ok(true)
}

pub struct WorkspacePaths {
    pub native_crate_dir: PathBuf,
    pub config_path: PathBuf,
    pub header_path: PathBuf,
}

impl WorkspacePaths {
    pub fn resolve() -> Result<Self, String> {
        let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
        let rust_dir = manifest_dir
            .parent()
            .ok_or_else(|| "Failed to resolve rust workspace directory".to_string())?;
        let native_crate_dir = rust_dir.join("crates").join("net-bridge-native");
        let config_path = native_crate_dir.join("cbindgen.toml");
        let header_path = native_crate_dir.join("include").join("netbridge.h");

        Ok(Self {
            native_crate_dir,
            config_path,
            header_path,
        })
    }
}

pub fn generate_header_bytes(paths: &WorkspacePaths) -> Result<Vec<u8>, String> {
    if !paths.config_path.exists() {
        return Err(format!(
            "cbindgen config file not found: {}",
            paths.config_path.display()
        ));
    }

    let config = cbindgen::Config::from_file(&paths.config_path).map_err(|e| {
        format!(
            "Failed to parse cbindgen config ({}): {e}",
            paths.config_path.display()
        )
    })?;

    let bindings = cbindgen::generate_with_config(&paths.native_crate_dir, config)
        .map_err(|e| format!("Failed to generate bindings with cbindgen: {e}"))?;

    let mut raw_bytes = Vec::new();
    bindings.write(&mut raw_bytes);

    Ok(normalize_line_endings(&raw_bytes))
}

pub fn run_update(paths: &WorkspacePaths) -> Result<(), String> {
    let generated = generate_header_bytes(paths)?;
    let modified = update_header_file(&paths.header_path, &generated).map_err(|e| {
        format!(
            "Failed to write header ({}): {e}",
            paths.header_path.display()
        )
    })?;

    if modified {
        println!("Updated {}", paths.header_path.display());
    } else {
        println!(
            "Header is already up to date: {}",
            paths.header_path.display()
        );
    }
    Ok(())
}

pub fn run_check(paths: &WorkspacePaths) -> Result<(), String> {
    let generated = generate_header_bytes(paths)?;
    let actual = fs::read(&paths.header_path).ok();
    let result = check_bytes(&generated, actual.as_deref());

    match result {
        CheckResult::Match => {
            println!(
                "Header check passed: {} is up to date.",
                paths.header_path.display()
            );
            Ok(())
        }
        CheckResult::Missing => {
            eprintln!("Header file is missing: {}", paths.header_path.display());
            eprintln!("{REMEDIATION_MSG}");
            Err("Header missing".to_string())
        }
        CheckResult::Mismatch => {
            eprintln!("{REMEDIATION_MSG}");
            Err("Header out of date".to_string())
        }
    }
}

fn print_usage() {
    eprintln!(
        "\
Usage: cargo xtask <command>

Commands:
    abi-header update    Generate and update include/netbridge.h
    abi-header check     Verify include/netbridge.h is up to date"
    );
}

fn main() -> ExitCode {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.is_empty() {
        print_usage();
        return ExitCode::from(2);
    }

    let paths = match WorkspacePaths::resolve() {
        Ok(p) => p,
        Err(e) => {
            eprintln!("Error resolving workspace paths: {e}");
            return ExitCode::FAILURE;
        }
    };

    match (
        args.first().map(String::as_str),
        args.get(1).map(String::as_str),
    ) {
        (Some("abi-header"), Some("update")) => match run_update(&paths) {
            Ok(()) => ExitCode::SUCCESS,
            Err(e) => {
                eprintln!("abi-header update failed: {e}");
                ExitCode::FAILURE
            }
        },
        (Some("abi-header"), Some("check")) => match run_check(&paths) {
            Ok(()) => ExitCode::SUCCESS,
            Err(_) => ExitCode::FAILURE,
        },
        _ => {
            print_usage();
            ExitCode::from(2)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn check_bytes_identical() {
        let expected = b"hello\nworld\n";
        assert_eq!(check_bytes(expected, Some(expected)), CheckResult::Match);
    }

    #[test]
    fn check_bytes_different() {
        let expected = b"hello\nworld\n";
        let actual = b"hello\nearth\n";
        assert_eq!(check_bytes(expected, Some(actual)), CheckResult::Mismatch);
    }

    #[test]
    fn check_bytes_missing() {
        let expected = b"hello\nworld\n";
        assert_eq!(check_bytes(expected, None), CheckResult::Missing);
    }

    #[test]
    fn normalize_line_endings_converts_crlf_and_ensures_trailing_newline() {
        let crlf = b"line1\r\nline2\r\nline3";
        let normalized = normalize_line_endings(crlf);
        assert_eq!(normalized, b"line1\nline2\nline3\n");

        let cr = b"line1\rline2";
        let normalized_cr = normalize_line_endings(cr);
        assert_eq!(normalized_cr, b"line1\nline2\n");

        let lf = b"line1\nline2\n";
        let normalized_lf = normalize_line_endings(lf);
        assert_eq!(normalized_lf, b"line1\nline2\n");
    }

    #[test]
    fn update_header_file_writes_changed_and_leaves_identical_untouched() {
        let temp_dir =
            std::env::temp_dir().join(format!("netbridge_xtask_test_{}", std::process::id()));
        let file_path = temp_dir.join("test_header.h");
        let _ = fs::remove_dir_all(&temp_dir);

        let content1 = b"// version 1\n";
        let modified = update_header_file(&file_path, content1).expect("write failed");
        assert!(modified, "First write should report modified");
        assert_eq!(fs::read(&file_path).unwrap(), content1);

        // Second write with identical content
        let modified_again = update_header_file(&file_path, content1).expect("write failed");
        assert!(
            !modified_again,
            "Identical write should not report modified"
        );

        // Write with changed content
        let content2 = b"// version 2\n";
        let modified_changed = update_header_file(&file_path, content2).expect("write failed");
        assert!(modified_changed, "Changed write should report modified");
        assert_eq!(fs::read(&file_path).unwrap(), content2);

        let _ = fs::remove_dir_all(&temp_dir);
    }

    #[test]
    fn missing_or_invalid_cbindgen_config_propagates_error() {
        let bad_paths = WorkspacePaths {
            native_crate_dir: PathBuf::from("/nonexistent/crate"),
            config_path: PathBuf::from("/nonexistent/cbindgen.toml"),
            header_path: PathBuf::from("/nonexistent/include/netbridge.h"),
        };
        let res = generate_header_bytes(&bad_paths);
        assert!(res.is_err(), "Missing config must return error");
        let err = res.unwrap_err();
        assert!(err.contains("cbindgen config file not found"));
    }
}
