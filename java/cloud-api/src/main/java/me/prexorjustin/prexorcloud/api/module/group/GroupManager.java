package me.prexorjustin.prexorcloud.api.module.group;

import java.util.concurrent.CompletableFuture;

import me.prexorjustin.prexorcloud.api.domain.GroupView;

public interface GroupManager {

    CompletableFuture<GroupView> createGroup(GroupCreateRequest request);

    CompletableFuture<Void> deleteGroup(String groupName);

    CompletableFuture<GroupView> updateGroup(String groupName, GroupUpdateRequest request);

    CompletableFuture<Void> setMaintenance(String groupName, boolean enabled, String message);
}
