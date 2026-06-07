package me.prexorjustin.prexorcloud.controller.rest.dto;

public record AddCatalogVersionRequest(
        String version, String downloadUrl, String sha256, String category, String configFormat) {}
