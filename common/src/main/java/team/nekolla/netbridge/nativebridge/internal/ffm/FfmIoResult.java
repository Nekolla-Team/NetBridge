package team.nekolla.netbridge.nativebridge.internal.ffm;

public record FfmIoResult(
        int status,
        int bytes
) {

    public boolean ok() {
        return status == FfmStatus.NB_OK;
    }

    public boolean wouldBlock() {
        return status == FfmStatus.NB_WOULD_BLOCK;
    }

    public boolean closed() {
        return status == FfmStatus.NB_CLOSED || status == FfmStatus.NB_NOT_FOUND;
    }

}
