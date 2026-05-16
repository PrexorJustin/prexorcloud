package me.prexorjustin.prexorcloud.api.client;

public record TransferResult(boolean success, String targetInstanceId, String failureReason) {

    public static TransferResult success(String targetInstanceId) {
        return new TransferResult(true, targetInstanceId, null);
    }

    public static TransferResult failure(String reason) {
        return new TransferResult(false, null, reason);
    }
}
