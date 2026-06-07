# Protocol Documentation
<a name="top"></a>

## Table of Contents

- [prexorcloud/cluster_membership_service.proto](#prexorcloud_cluster_membership_service-proto)
    - [KnownPeer](#me-prexorjustin-prexorcloud-protocol-KnownPeer)
    - [RequestJoinRequest](#me-prexorjustin-prexorcloud-protocol-RequestJoinRequest)
    - [RequestJoinResponse](#me-prexorjustin-prexorcloud-protocol-RequestJoinResponse)
  
    - [ClusterMembership](#me-prexorjustin-prexorcloud-protocol-ClusterMembership)
  
- [Scalar Value Types](#scalar-value-types)



<a name="prexorcloud_cluster_membership_service-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## prexorcloud/cluster_membership_service.proto



<a name="me-prexorjustin-prexorcloud-protocol-KnownPeer"></a>

### KnownPeer



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| node_id | [string](#string) |  |  |
| raft_addr | [string](#string) |  |  |






<a name="me-prexorjustin-prexorcloud-protocol-RequestJoinRequest"></a>

### RequestJoinRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| token | [string](#string) |  | Wire token: &#34;prexor-jt:v1:&lt;base64url(payload)&gt;.&lt;base64url(hmac)&gt;&#34;. |
| node_id | [string](#string) |  | Human-readable label for the joining controller (e.g. &#34;controller-2&#34;). The state machine pins this to AddMember.label. |
| raft_addr | [string](#string) |  | host:port the joiner&#39;s Ratis listens on (cluster-internal). |
| rest_addr | [string](#string) |  | host:port the joiner&#39;s REST listens on (operator-facing). |
| grpc_addr | [string](#string) |  | host:port the joiner&#39;s controller gRPC listens on (modules/daemons). |
| csr_der | [bytes](#bytes) |  | PKCS#10 CSR (DER) — joiner&#39;s ephemeral keypair &#43; the SANs it wants on its leaf. |






<a name="me-prexorjustin-prexorcloud-protocol-RequestJoinResponse"></a>

### RequestJoinResponse



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| signed_cert_der | [bytes](#bytes) |  | X.509 leaf cert signed by the cluster CA. The joiner pairs this with its private key (held locally, never leaves the joining host) to terminate cluster-trusted TLS on its own gRPC &#43; Ratis endpoints. |
| ca_cert_der | [bytes](#bytes) |  | Cluster CA cert. The joiner trusts this for incoming cluster traffic. |
| cluster_id | [string](#string) |  | The cluster&#39;s stable identifier. The joiner writes it to its controller.yml mirror so a future restart can refuse to bind to a different cluster&#39;s data. |
| current_peers | [KnownPeer](#me-prexorjustin-prexorcloud-protocol-KnownPeer) | repeated | Snapshot of the current Raft peer set (node_id &#43; raft_addr) at the moment the join was processed. The joiner needs these to build the initial known RaftGroup before calling GroupManagementApi.add() on itself — the token&#39;s joinAddrs[] is the controller gRPC port, which is unrelated to the Raft transport port. Includes the leader and every other accepted member but does NOT include the joiner itself. |





 

 

 


<a name="me-prexorjustin-prexorcloud-protocol-ClusterMembership"></a>

### ClusterMembership
Controller-to-controller membership. A joining controller calls RequestJoin
on any existing controller (which redirects to the leader if needed). The
service validates the join token&#39;s HMAC against the cluster seed secret,
atomically redeems it via Raft, signs the joiner&#39;s CSR with the cluster CA,
and returns the signed cert &#43; CA cert &#43; cluster id.

The actual Ratis joint-consensus member-add happens once the joiner has its
new TLS material — it brings up its own Ratis server and calls
GroupManagementApi.add() on itself; the leader then expands the group via
setConfiguration. See docs/engineering/cluster-join-plan.md.

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| RequestJoin | [RequestJoinRequest](#me-prexorjustin-prexorcloud-protocol-RequestJoinRequest) | [RequestJoinResponse](#me-prexorjustin-prexorcloud-protocol-RequestJoinResponse) |  |

 



## Scalar Value Types

| .proto Type | Notes | C++ | Java | Python | Go | C# | PHP | Ruby |
| ----------- | ----- | --- | ---- | ------ | -- | -- | --- | ---- |
| <a name="double" /> double |  | double | double | float | float64 | double | float | Float |
| <a name="float" /> float |  | float | float | float | float32 | float | float | Float |
| <a name="int32" /> int32 | Uses variable-length encoding. Inefficient for encoding negative numbers – if your field is likely to have negative values, use sint32 instead. | int32 | int | int | int32 | int | integer | Bignum or Fixnum (as required) |
| <a name="int64" /> int64 | Uses variable-length encoding. Inefficient for encoding negative numbers – if your field is likely to have negative values, use sint64 instead. | int64 | long | int/long | int64 | long | integer/string | Bignum |
| <a name="uint32" /> uint32 | Uses variable-length encoding. | uint32 | int | int/long | uint32 | uint | integer | Bignum or Fixnum (as required) |
| <a name="uint64" /> uint64 | Uses variable-length encoding. | uint64 | long | int/long | uint64 | ulong | integer/string | Bignum or Fixnum (as required) |
| <a name="sint32" /> sint32 | Uses variable-length encoding. Signed int value. These more efficiently encode negative numbers than regular int32s. | int32 | int | int | int32 | int | integer | Bignum or Fixnum (as required) |
| <a name="sint64" /> sint64 | Uses variable-length encoding. Signed int value. These more efficiently encode negative numbers than regular int64s. | int64 | long | int/long | int64 | long | integer/string | Bignum |
| <a name="fixed32" /> fixed32 | Always four bytes. More efficient than uint32 if values are often greater than 2^28. | uint32 | int | int | uint32 | uint | integer | Bignum or Fixnum (as required) |
| <a name="fixed64" /> fixed64 | Always eight bytes. More efficient than uint64 if values are often greater than 2^56. | uint64 | long | int/long | uint64 | ulong | integer/string | Bignum |
| <a name="sfixed32" /> sfixed32 | Always four bytes. | int32 | int | int | int32 | int | integer | Bignum or Fixnum (as required) |
| <a name="sfixed64" /> sfixed64 | Always eight bytes. | int64 | long | int/long | int64 | long | integer/string | Bignum |
| <a name="bool" /> bool |  | bool | boolean | boolean | bool | bool | boolean | TrueClass/FalseClass |
| <a name="string" /> string | A string must always contain UTF-8 encoded or 7-bit ASCII text. | string | String | str/unicode | string | string | string | String (UTF-8) |
| <a name="bytes" /> bytes | May contain any arbitrary sequence of bytes. | string | ByteString | str | []byte | ByteString | string | String (ASCII-8BIT) |

