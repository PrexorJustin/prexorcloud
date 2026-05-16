package me.prexorjustin.prexorcloud.controller.group;

public record GroupRuntimeTarget(String platform, String platformVersion, GroupRuntimeFamily family) {

    public GroupRuntimeTarget {
        if (platform == null) {
            platform = "";
        }
        if (platformVersion == null) {
            platformVersion = "";
        }
        if (family == null) {
            family = GroupRuntimeFamily.fromPlatform(platform);
        }
    }
}
