package me.prexorjustin.prexorcloud.controller.rest.dto;

public record UpdateCatalogVersionRequest(String version, String downloadUrl, String sha256) {}
