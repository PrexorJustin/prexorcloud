package me.prexorjustin.prexorcloud.controller.rest.dto;

public record UserDto(String username, String role, String email, String createdAt, String lastLoginAt) {}
