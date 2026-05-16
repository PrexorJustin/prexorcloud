package me.prexorjustin.prexorcloud.controller.rest.dto;

public record PasswordResetComplete(String token, String newPassword) {}
