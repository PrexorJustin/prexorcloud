package me.prexorjustin.prexorcloud.controller.rest.dto;

public record RestoreRequest(String id, Boolean dryRun, Boolean filesystem, Boolean datastores) {}
