package me.prexorjustin.prexorcloud.controller.rest.dto;

public record ChangePasswordRequest(String currentPassword, String newPassword) {}
