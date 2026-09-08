//! Bridge-surface constant tests for ABI version and status-code contracts.

use super::STATE_CONNECTED;

#[test]
fn abi_constants() {
    assert_eq!(STATE_CONNECTED, 1);
}
