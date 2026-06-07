package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.List;

public record WorkloadCredentialPage(List<WorkloadCredentialDto> data, int page, int pageSize, int total) {}
