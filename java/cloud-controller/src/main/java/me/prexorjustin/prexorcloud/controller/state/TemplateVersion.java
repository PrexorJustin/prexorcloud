package me.prexorjustin.prexorcloud.controller.state;

public record TemplateVersion(String templateName, String hash, long sizeBytes, String createdAt) {}
