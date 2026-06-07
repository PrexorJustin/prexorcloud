package me.prexorjustin.prexorcloud.api.module.service;

/**
 * Metadata describing who registered a service, used for observability and
 * (in slice 4) capability attribution.
 *
 * @param key the service key the provider registered
 * @param moduleName name of the module that provided the service
 * @param moduleVersion version of that module at registration time
 */
public record ServiceProvider(ServiceKey<?> key, String moduleName, String moduleVersion) {}
