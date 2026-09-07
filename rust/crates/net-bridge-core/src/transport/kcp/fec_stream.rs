//! FECStream: an RS(255,223) block error-correction layer above a reliable stream.

use std::io;
use std::pin::Pin;
use std::task::Poll;

use fec::{RsDecoder, RsEncoder};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

/// Total RS codeword length (GF(2^8), CCSDS standard n=255).
pub const CODEWORD_LEN: usize = 255;
/// RS message-region length (k=223).
pub const MESSAGE_LEN: usize = 223;
/// Maximum payload per block: the first 2 bytes of the message region are a length prefix.
pub const PAYLOAD_CAP: usize = MESSAGE_LEN - 2;

type Codeword = [u8; CODEWORD_LEN];

pub struct FecStream<Io> {
    inner: Io,
    encoder: RsEncoder,
    decoder: RsDecoder,
    // ---- Read-side state ----
    /// Accumulator for codewords awaiting decode.
    rcw: Codeword,
    rfill: usize,
    /// Most recently decoded plaintext and consumption cursor (`rplain[rpos..rlen]` is unconsumed).
    rplain: [u8; MESSAGE_LEN],
    rpos: usize,
    rlen: usize,
    /// Poison flag set after RS decoding exceeds correction capacity; all later operations return
    /// InvalidData.
    poisoned: bool,
    // ---- Write-side state ----
    /// Message-region accumulator: `wmsg[..2]` is filled with the length when emitting a block, and
    /// payload begins at index 2.
    wmsg: [u8; MESSAGE_LEN],
    wfill: usize,
    /// Pending codeword and offset used to resume partial writes; None means no codeword is in flight.
    pending: Option<(Box<Codeword>, usize)>,
}

impl<Io> FecStream<Io> {
    pub fn new(inner: Io) -> Self {
        Self {
            inner,
            encoder: RsEncoder::new_ccsds(),
            decoder: RsDecoder::new_ccsds(),
            rcw: [0u8; CODEWORD_LEN],
            rfill: 0,
            rplain: [0u8; MESSAGE_LEN],
            rpos: 0,
            rlen: 0,
            poisoned: false,
            wmsg: [0u8; MESSAGE_LEN],
            wfill: 0,
            pending: None,
        }
    }

    /// Returns the inner stream. The caller must ensure it has been flushed/shut down first.
    pub fn into_inner(self) -> Io {
        self.inner
    }

    fn poison(err_kind: io::ErrorKind, msg: &'static str) -> io::Error {
        io::Error::new(err_kind, msg)
    }
}

impl<Io: AsyncRead + Unpin> FecStream<Io> {
    fn poll_decode(
        &mut self,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<io::Result<bool>> {
        use std::task::Poll;
        if self.poisoned {
            return Poll::Ready(Err(Self::poison(
                io::ErrorKind::InvalidData,
                "fec stream poisoned",
            )));
        }
        loop {
            if self.rfill == CODEWORD_LEN {
                let result = self.decoder.decode(&self.rcw, &mut self.rplain);
                match result {
                    Ok(_corrected) => {
                        let len = u16::from_be_bytes([self.rplain[0], self.rplain[1]]) as usize;
                        if len > PAYLOAD_CAP {
                            self.poisoned = true;
                            return Poll::Ready(Err(Self::poison(
                                io::ErrorKind::InvalidData,
                                "fec: corrupted length prefix",
                            )));
                        }
                        self.rpos = 2;
                        self.rlen = 2 + len;
                        self.rfill = 0;
                        return Poll::Ready(Ok(true));
                    }
                    Err(e) => {
                        self.poisoned = true;
                        return Poll::Ready(Err(io::Error::new(
                            io::ErrorKind::InvalidData,
                            format!("fec decode failed (corruption beyond capability): {e}"),
                        )));
                    }
                }
            }
            let mut buf = ReadBuf::new(&mut self.rcw[self.rfill..]);
            match Pin::new(&mut self.inner).poll_read(cx, &mut buf) {
                Poll::Ready(Ok(())) => {
                    let n = buf.filled().len();
                    if n == 0 {
                        if self.rfill == 0 {
                            return Poll::Ready(Ok(false));
                        }
                        self.poisoned = true;
                        return Poll::Ready(Err(Self::poison(
                            io::ErrorKind::UnexpectedEof,
                            "fec: truncated codeword",
                        )));
                    }
                    self.rfill += n;
                }
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            }
        }
    }
}

impl<Io: AsyncRead + Unpin> AsyncRead for FecStream<Io> {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if this.poisoned {
            return Poll::Ready(Err(Self::poison(
                io::ErrorKind::InvalidData,
                "fec stream poisoned",
            )));
        }
        if this.rpos >= this.rlen {
            match this.poll_decode(cx) {
                Poll::Ready(Ok(true)) => {}
                Poll::Ready(Ok(false)) => return Poll::Ready(Ok(())),
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            }
        }
        let avail = this.rlen - this.rpos;
        let n = avail.min(buf.remaining());
        buf.put_slice(&this.rplain[this.rpos..this.rpos + n]);
        this.rpos += n;
        Poll::Ready(Ok(()))
    }
}

impl<Io: AsyncWrite + Unpin> AsyncWrite for FecStream<Io> {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        if this.poisoned {
            return Poll::Ready(Err(Self::poison(
                io::ErrorKind::InvalidData,
                "fec stream poisoned",
            )));
        }
        match Self::poll_flush_pending(this, cx)? {
            Poll::Ready(()) => {}
            Poll::Pending => return Poll::Pending,
        }
        if buf.is_empty() {
            return Poll::Ready(Err(io::Error::new(
                io::ErrorKind::WriteZero,
                "fec: empty write",
            )));
        }
        let accept = (PAYLOAD_CAP - this.wfill).min(buf.len());
        this.wmsg[2 + this.wfill..2 + this.wfill + accept].copy_from_slice(&buf[..accept]);
        this.wfill += accept;
        if this.wfill == PAYLOAD_CAP {
            let cw = this.encode_accumulated();
            this.pending = Some((cw, 0));
            let _ = Self::poll_flush_pending(this, cx)?;
        }
        Poll::Ready(Ok(accept))
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut std::task::Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if this.poisoned {
            return Poll::Ready(Err(Self::poison(
                io::ErrorKind::InvalidData,
                "fec stream poisoned",
            )));
        }
        match Self::poll_flush_pending(this, cx)? {
            Poll::Ready(()) => {}
            Poll::Pending => return Poll::Pending,
        }
        if this.wfill > 0 {
            let cw = this.encode_accumulated();
            this.pending = Some((cw, 0));
            match Self::poll_flush_pending(this, cx)? {
                Poll::Ready(()) => {}
                Poll::Pending => return Poll::Pending,
            }
        }
        Pin::new(&mut this.inner).poll_flush(cx)
    }

    fn poll_shutdown(
        self: Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        match AsyncWrite::poll_flush(Pin::new(this), cx) {
            Poll::Ready(Ok(())) => {}
            other => return other,
        }
        Pin::new(&mut this.inner).poll_shutdown(cx)
    }
}

impl<Io: AsyncWrite + Unpin> FecStream<Io> {
    fn encode_accumulated(&mut self) -> Box<Codeword> {
        let payload = self.wfill;
        self.wmsg[..2].copy_from_slice(&(payload as u16).to_be_bytes());
        for b in &mut self.wmsg[2 + payload..] {
            *b = 0;
        }
        let mut cw: Box<Codeword> = Box::new([0u8; CODEWORD_LEN]);
        let _ = self.encoder.encode(&self.wmsg, &mut cw[..]);
        self.wfill = 0;
        cw
    }

    fn poll_flush_pending(
        this: &mut Self,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<io::Result<()>> {
        use std::task::Poll;
        let Some((cw, offset)) = this.pending.as_mut() else {
            return Poll::Ready(Ok(()));
        };
        loop {
            match Pin::new(&mut this.inner).poll_write(cx, &cw[*offset..]) {
                Poll::Ready(Ok(n)) => {
                    *offset += n;
                    if *offset == CODEWORD_LEN {
                        this.pending = None;
                        return Poll::Ready(Ok(()));
                    }
                }
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            }
        }
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::pin::Pin;
    use std::task::{Context, Poll};
    use tokio::io::{AsyncReadExt, AsyncWriteExt, ReadBuf, duplex};

    struct CorruptRead<S> {
        inner: S,
        blocks: usize,
        flips_per_block: usize,
        pos: u64,
    }

    impl<S: AsyncRead + Unpin> AsyncRead for CorruptRead<S> {
        fn poll_read(
            self: Pin<&mut Self>,
            cx: &mut Context<'_>,
            buf: &mut ReadBuf<'_>,
        ) -> Poll<io::Result<()>> {
            let this = self.get_mut();
            let cap = buf.remaining();
            if cap == 0 {
                return Poll::Ready(Ok(()));
            }
            let mut scratch = vec![0u8; cap.min(CODEWORD_LEN * 4)];
            let mut rb = ReadBuf::new(&mut scratch);
            match Pin::new(&mut this.inner).poll_read(cx, &mut rb)? {
                Poll::Ready(()) => {
                    let mut out = rb.filled().to_vec();
                    for (off, byte) in out.iter_mut().enumerate() {
                        let abs = this.pos + off as u64;
                        let block = (abs / CODEWORD_LEN as u64) as usize;
                        if block >= this.blocks {
                            continue;
                        }
                        let idx_in_block = (abs % CODEWORD_LEN as u64) as usize;
                        let flip_at = |i: usize| (i * 37 + 11) % CODEWORD_LEN;
                        if idx_in_block != 0
                            && (0..this.flips_per_block).any(|i| flip_at(i) == idx_in_block)
                        {
                            *byte ^= 0x5a;
                        }
                    }
                    this.pos += out.len() as u64;
                    buf.put_slice(&out);
                    Poll::Ready(Ok(()))
                }
                Poll::Pending => Poll::Pending,
            }
        }
    }

    #[tokio::test]
    async fn roundtrip_small_and_multi_block() {
        let (a, b) = duplex(64 * 1024);
        let (mut tx, mut rx) = (FecStream::new(a), FecStream::new(b));

        tx.write_all(b"hello fec").await.expect("send small");
        tx.write_all(&vec![0xABu8; PAYLOAD_CAP * 3 + 17])
            .await
            .expect("send multi");
        tx.flush().await.expect("flush tail");

        let mut out = vec![0u8; 9];
        rx.read_exact(&mut out).await.expect("recv small");
        assert_eq!(out, b"hello fec");

        let total = PAYLOAD_CAP * 3 + 17;
        let mut big = vec![0u8; total];
        rx.read_exact(&mut big).await.expect("recv big");
        assert_eq!(big, vec![0xABu8; total]);
    }

    #[tokio::test]
    async fn corrects_intra_block_corruption() {
        let (a, b) = duplex(64 * 1024);
        let corrupted = CorruptRead {
            inner: a,
            blocks: 4,
            flips_per_block: 8,
            pos: 0,
        };
        let (mut tx, mut rx) = (FecStream::new(b), FecStream::new(corrupted));

        let payload: Vec<u8> = (0..PAYLOAD_CAP * 2 + 100).map(|i| i as u8).collect();
        tx.write_all(&payload).await.expect("send");
        tx.flush().await.expect("flush");

        let mut got = vec![0u8; payload.len()];
        rx.read_exact(&mut got)
            .await
            .expect("recv under corruption");
        assert_eq!(
            got, payload,
            "RS must correct corruption up to t symbols back to the original payload"
        );
    }

    #[tokio::test]
    async fn fails_when_corruption_exceeds_capability() {
        let (a, b) = duplex(64 * 1024);
        let corrupted = CorruptRead {
            inner: a,
            blocks: 1,
            flips_per_block: 40,
            pos: 0,
        };
        let (mut tx, mut rx) = (FecStream::new(b), FecStream::new(corrupted));

        tx.write_all(&vec![7u8; 400]).await.expect("send");
        tx.flush().await.expect("flush");

        let mut tmp = vec![0u8; 512];
        let res = rx.read(&mut tmp).await;
        assert!(
            matches!(&res, Err(e) if e.kind() == io::ErrorKind::InvalidData),
            "Corruption beyond the correction limit must return InvalidData; got {res:?}"
        );
    }

    #[tokio::test]
    async fn shutdown_flushes_tail_then_eof() {
        let (a, b) = duplex(64 * 1024);
        let mut tx = FecStream::new(a);
        let mut rx = FecStream::new(b);

        tx.write_all(b"tail").await.expect("send");
        tx.shutdown().await.expect("shutdown");

        let mut out = vec![0u8; 4];
        rx.read_exact(&mut out).await.expect("recv tail");
        assert_eq!(out, b"tail");
        assert_eq!(rx.read(&mut out).await.expect("eof"), 0);
    }
}
