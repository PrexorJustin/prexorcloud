# Protocol Documentation
<a name="top"></a>

## Table of Contents

- [prexorcloud/daemon_service.proto](#prexorcloud_daemon_service-proto)
    - [BootstrapCacheEntry](#me-prexorjustin-prexorcloud-protocol-BootstrapCacheEntry)
    - [CacheStatus](#me-prexorjustin-prexorcloud-protocol-CacheStatus)
    - [CompositionPlan](#me-prexorjustin-prexorcloud-protocol-CompositionPlan)
    - [ConfigPatch](#me-prexorjustin-prexorcloud-protocol-ConfigPatch)
    - [ConsoleOutput](#me-prexorjustin-prexorcloud-protocol-ConsoleOutput)
    - [ControllerMessage](#me-prexorjustin-prexorcloud-protocol-ControllerMessage)
    - [CrashReport](#me-prexorjustin-prexorcloud-protocol-CrashReport)
    - [DaemonLogRecord](#me-prexorjustin-prexorcloud-protocol-DaemonLogRecord)
    - [DaemonLogRecord.MdcEntry](#me-prexorjustin-prexorcloud-protocol-DaemonLogRecord-MdcEntry)
    - [DaemonMessage](#me-prexorjustin-prexorcloud-protocol-DaemonMessage)
    - [ErrorReport](#me-prexorjustin-prexorcloud-protocol-ErrorReport)
    - [EventSubscribe](#me-prexorjustin-prexorcloud-protocol-EventSubscribe)
    - [EventUnsubscribe](#me-prexorjustin-prexorcloud-protocol-EventUnsubscribe)
    - [ExtensionArtifact](#me-prexorjustin-prexorcloud-protocol-ExtensionArtifact)
    - [FileEntry](#me-prexorjustin-prexorcloud-protocol-FileEntry)
    - [Handshake](#me-prexorjustin-prexorcloud-protocol-Handshake)
    - [Handshake.LabelsEntry](#me-prexorjustin-prexorcloud-protocol-Handshake-LabelsEntry)
    - [HandshakeAck](#me-prexorjustin-prexorcloud-protocol-HandshakeAck)
    - [HostInfo](#me-prexorjustin-prexorcloud-protocol-HostInfo)
    - [InstanceFileContent](#me-prexorjustin-prexorcloud-protocol-InstanceFileContent)
    - [InstanceFileTree](#me-prexorjustin-prexorcloud-protocol-InstanceFileTree)
    - [InstanceStatusUpdate](#me-prexorjustin-prexorcloud-protocol-InstanceStatusUpdate)
    - [JarCacheEntry](#me-prexorjustin-prexorcloud-protocol-JarCacheEntry)
    - [ModuleEvent](#me-prexorjustin-prexorcloud-protocol-ModuleEvent)
    - [ModuleInstall](#me-prexorjustin-prexorcloud-protocol-ModuleInstall)
    - [ModuleStateUpdate](#me-prexorjustin-prexorcloud-protocol-ModuleStateUpdate)
    - [ModuleUninstall](#me-prexorjustin-prexorcloud-protocol-ModuleUninstall)
    - [NodeStatus](#me-prexorjustin-prexorcloud-protocol-NodeStatus)
    - [Ping](#me-prexorjustin-prexorcloud-protocol-Ping)
    - [Pong](#me-prexorjustin-prexorcloud-protocol-Pong)
    - [PreWarmCache](#me-prexorjustin-prexorcloud-protocol-PreWarmCache)
    - [PreWarmEntry](#me-prexorjustin-prexorcloud-protocol-PreWarmEntry)
    - [ReadInstanceFile](#me-prexorjustin-prexorcloud-protocol-ReadInstanceFile)
    - [RequestCacheStatus](#me-prexorjustin-prexorcloud-protocol-RequestCacheStatus)
    - [RunningInstance](#me-prexorjustin-prexorcloud-protocol-RunningInstance)
    - [RuntimeArtifact](#me-prexorjustin-prexorcloud-protocol-RuntimeArtifact)
    - [RuntimeIsolation](#me-prexorjustin-prexorcloud-protocol-RuntimeIsolation)
    - [SendCommand](#me-prexorjustin-prexorcloud-protocol-SendCommand)
    - [ShutdownNode](#me-prexorjustin-prexorcloud-protocol-ShutdownNode)
    - [ShutdownNodeAck](#me-prexorjustin-prexorcloud-protocol-ShutdownNodeAck)
    - [StartInstance](#me-prexorjustin-prexorcloud-protocol-StartInstance)
    - [StartInstance.EnvEntry](#me-prexorjustin-prexorcloud-protocol-StartInstance-EnvEntry)
    - [StartInstanceAck](#me-prexorjustin-prexorcloud-protocol-StartInstanceAck)
    - [StopInstance](#me-prexorjustin-prexorcloud-protocol-StopInstance)
    - [StopInstanceAck](#me-prexorjustin-prexorcloud-protocol-StopInstanceAck)
    - [TemplateCacheEntry](#me-prexorjustin-prexorcloud-protocol-TemplateCacheEntry)
    - [TemplateData](#me-prexorjustin-prexorcloud-protocol-TemplateData)
    - [TemplateRef](#me-prexorjustin-prexorcloud-protocol-TemplateRef)
    - [TemplateRequest](#me-prexorjustin-prexorcloud-protocol-TemplateRequest)
    - [TemplateUpToDate](#me-prexorjustin-prexorcloud-protocol-TemplateUpToDate)
    - [WalkInstanceFiles](#me-prexorjustin-prexorcloud-protocol-WalkInstanceFiles)
  
    - [ConfigFormat](#me-prexorjustin-prexorcloud-protocol-ConfigFormat)
    - [InstanceCategory](#me-prexorjustin-prexorcloud-protocol-InstanceCategory)
    - [InstanceState](#me-prexorjustin-prexorcloud-protocol-InstanceState)
    - [StartFailureDisposition](#me-prexorjustin-prexorcloud-protocol-StartFailureDisposition)
    - [StartPreparationStage](#me-prexorjustin-prexorcloud-protocol-StartPreparationStage)
  
    - [DaemonService](#me-prexorjustin-prexorcloud-protocol-DaemonService)
  
- [Scalar Value Types](#scalar-value-types)



<a name="prexorcloud_daemon_service-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## prexorcloud/daemon_service.proto



<a name="me-prexorjustin-prexorcloud-protocol-BootstrapCacheEntry"></a>

### BootstrapCacheEntry



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| config_format | [ConfigFormat](#me-prexorjustin-prexorcloud-protocol-ConfigFormat) |  |  |
| version | [string](#string) |  |  |
| has_cds | [bool](#bool) |  |  |
| size_bytes | [int64](#int64) |  |  |






<a name="me-prexorjustin-prexorcloud-protocol-CacheStatus"></a>

### CacheStatus



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| templates | [TemplateCacheEntry](#me-prexorjustin-prexorcloud-protocol-TemplateCacheEntry) | repeated |  |
| jars | [JarCacheEntry](#me-prexorjustin-prexorcloud-protocol-JarCacheEntry) | repeated |  |
| bootstraps | [BootstrapCacheEntry](#me-prexorjustin-prexorcloud-protocol-BootstrapCacheEntry) | repeated |  |
| total_size_bytes | [int64](#int64) |  |  |






<a name="me-prexorjustin-prexorcloud-protocol-CompositionPlan"></a>

### CompositionPlan



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| plan_hash | [string](#string) |  |  |
| runtime | [RuntimeArtifact](#me-prexorjustin-prexorcloud-protocol-RuntimeArtifact) |  |  |
| templates | [TemplateRef](#me-prexorjustin-prexorcloud-protocol-TemplateRef) | repeated |  |
| extensions | [ExtensionArtifact](#me-prexorjustin-prexorcloud-protocol-ExtensionArtifact) | repeated |  |
| config_patches | [ConfigPatch](#me-prexorjustin-prexorcloud-protocol-ConfigPatch) | repeated |  |
| isolation | [RuntimeIsolation](#me-prexorjustin-prexorcloud-protocol-RuntimeIsolation) |  |  |






<a name="me-prexorjustin-prexorcloud-protocol-ConfigPatch"></a>

### ConfigPatch



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| file | [string](#string) |  |  |
| key | [string](#string) |  |  |
| value | [string](#string) |  |  |






<a name="me-prexorjustin-prexorcloud-protocol-ConsoleOutput"></a>

### ConsoleOutput



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| instance_id | [string](#string) |  |  |
| line | [string](#string) |  |  |
| timestamp_ms | [int64](#int64) |  | Epoch milliseconds |






<a name="me-prexorjustin-prexorcloud-protocol-ControllerMessage"></a>

### ControllerMessage
Envelope: controller -&gt; daemon


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| handshake_ack | [HandshakeAck](#me-prexorjustin-prexorcloud-protocol-HandshakeAck) |  |  |
| start_instance | [StartInstance](#me-prexorjustin-prexorcloud-protocol-StartInstance) |  |  |
| stop_instance | [StopInstance](#me-prexorjustin-prexorcloud-protocol-StopInstance) |  |  |
| send_command | [SendCommand](#me-prexorjustin-prexorcloud-protocol-SendCommand) |  |  |
| ping | [Ping](#me-prexorjustin-prexorcloud-protocol-Ping) |  |  |
| template_data | [TemplateData](#me-prexorjustin-prexorcloud-protocol-TemplateData) |  |  |
| template_up_to_date | [TemplateUpToDate](#me-prexorjustin-prexorcloud-protocol-TemplateUpToDate) |  |  |
| shutdown_node | [ShutdownNode](#me-prexorjustin-prexorcloud-protocol-ShutdownNode) |  |  |
| pre_warm_cache | [PreWarmCache](#me-prexorjustin-prexorcloud-protocol-PreWarmCache) |  |  |
| request_cache_status | [RequestCacheStatus](#me-prexorjustin-prexorcloud-protocol-RequestCacheStatus) |  |  |
| error_report | [ErrorReport](#me-prexorjustin-prexorcloud-protocol-ErrorReport) |  | Error notifications from controller |
| module_install | [ModuleInstall](#me-prexorjustin-prexorcloud-protocol-ModuleInstall) |  | Push a daemon-host platform module to a daemon |
| module_uninstall | [ModuleUninstall](#me-prexorjustin-prexorcloud-protocol-ModuleUninstall) |  | Drop a daemon-host platform module on a daemon |
| module_event | [ModuleEvent](#me-prexorjustin-prexorcloud-protocol-ModuleEvent) |  | Forward a controller-bus event the daemon subscribed to |
| walk_instance_files | [WalkInstanceFiles](#me-prexorjustin-prexorcloud-protocol-WalkInstanceFiles) |  | Request a structure-only filetree of an instance dir |
| read_instance_file | [ReadInstanceFile](#me-prexorjustin-prexorcloud-protocol-ReadInstanceFile) |  | Request the bytes of a single file under an instance dir |






<a name="me-prexorjustin-prexorcloud-protocol-CrashReport"></a>

### CrashReport



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| instance_id | [string](#string) |  | REQUIRED |
| group | [string](#string) |  | REQUIRED |
| exit_code | [int32](#int32) |  |  |
| log_tail | [string](#string) | repeated | Last N lines of stdout/stderr |
| uptime_ms | [int64](#int64) |  | How long the instance was alive before crash |






<a name="me-prexorjustin-prexorcloud-protocol-DaemonLogRecord"></a>

### DaemonLogRecord
Single Logback event mirrored from the daemon JVM up to the controller so
that `prexorctl logs daemon &lt;node-id&gt;` can render it through the same ring
buffer surface used for controller logs. The daemon does not buffer locally:
when the controller stream is down, records are dropped (the daemon&#39;s
rolling FILE appender keeps disk-side history for forensics).


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| timestamp_ms | [int64](#int64) |  | Epoch milliseconds |
| level | [string](#string) |  | TRACE | DEBUG | INFO | WARN | ERROR |
| logger | [string](#string) |  | Logger name (max 256 chars) |
| thread | [string](#string) |  | Originating thread name (max 128 chars) |
| message | [string](#string) |  | Formatted message (max 8192 chars; truncated by daemon) |
| throwable | [string](#string) |  | Optional rendered stack trace (max 32768 chars) |
| mdc | [DaemonLogRecord.MdcEntry](#me-prexorjustin-prexorcloud-protocol-DaemonLogRecord-MdcEntry) | repeated | Optional MDC snapshot |






<a name="me-prexorjustin-prexorcloud-protocol-DaemonLogRecord-MdcEntry"></a>

### DaemonLogRecord.MdcEntry



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| key | [string](#string) |  |  |
| value | [string](#string) |  |  |






<a name="me-prexorjustin-prexorcloud-protocol-DaemonMessage"></a>

### DaemonMessage
Envelope: daemon -&gt; controller


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| handshake | [Handshake](#me-prexorjustin-prexorcloud-protocol-Handshake) |  |  |
| node_status | [NodeStatus](#me-prexorjustin-prexorcloud-protocol-NodeStatus) |  |  |
| instance_status | [InstanceStatusUpdate](#me-prexorjustin-prexorcloud-protocol-InstanceStatusUpdate) |  |  |
| console_output | [ConsoleOutput](#me-prexorjustin-prexorcloud-protocol-ConsoleOutput) |  |  |
| crash_report | [CrashReport](#me-prexorjustin-prexorcloud-protocol-CrashReport) |  |  |
| pong | [Pong](#me-prexorjustin-prexorcloud-protocol-Pong) |  |  |
| template_request | [TemplateRequest](#me-prexorjustin-prexorcloud-protocol-TemplateRequest) |  |  |
| cache_status | [CacheStatus](#me-prexorjustin-prexorcloud-protocol-CacheStatus) |  |  |
| error_report | [ErrorReport](#me-prexorjustin-prexorcloud-protocol-ErrorReport) |  | Partial failure reports from daemon |
| shutdown_node_ack | [ShutdownNodeAck](#me-prexorjustin-prexorcloud-protocol-ShutdownNodeAck) |  | Acknowledgment of shutdown request |
| start_instance_ack | [StartInstanceAck](#me-prexorjustin-prexorcloud-protocol-StartInstanceAck) |  | Delivery confirmation for StartInstance |
| stop_instance_ack | [StopInstanceAck](#me-prexorjustin-prexorcloud-protocol-StopInstanceAck) |  | Delivery confirmation for StopInstance |
| daemon_log_record | [DaemonLogRecord](#me-prexorjustin-prexorcloud-protocol-DaemonLogRecord) |  | Single Logback event from the daemon JVM |
| module_state_update | [ModuleStateUpdate](#me-prexorjustin-prexorcloud-protocol-ModuleStateUpdate) |  | Daemon-side platform-module state report |
| event_subscribe | [EventSubscribe](#me-prexorjustin-prexorcloud-protocol-EventSubscribe) |  | Daemon registers interest in controller-bus event types |
| event_unsubscribe | [EventUnsubscribe](#me-prexorjustin-prexorcloud-protocol-EventUnsubscribe) |  | Daemon drops interest in controller-bus event types |
| instance_file_tree | [InstanceFileTree](#me-prexorjustin-prexorcloud-protocol-InstanceFileTree) |  | Reply to WalkInstanceFiles |
| instance_file_content | [InstanceFileContent](#me-prexorjustin-prexorcloud-protocol-InstanceFileContent) |  | Reply to ReadInstanceFile |






<a name="me-prexorjustin-prexorcloud-protocol-ErrorReport"></a>

### ErrorReport
Error report for partial failures. Either side can send this to signal
a non-fatal error that doesn&#39;t warrant terminating the stream.


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| error_code | [string](#string) |  | Machine-readable code (e.g. &#34;CACHE_DOWNLOAD_FAILED&#34;) |
| error_message | [string](#string) |  | Human-readable description |
| context | [string](#string) |  | Related resource (instance ID, template name, etc.) |
| retry_after_seconds | [int32](#int32) |  | 0 = no retry suggestion |






<a name="me-prexorjustin-prexorcloud-protocol-EventSubscribe"></a>

### EventSubscribe
Daemon registers interest in one or more controller-bus event types.
event_types are fully-qualified Java class names (e.g.
&#34;me.prexorjustin.prexorcloud.api.event.GroupCreatedEvent&#34;). The controller
subscribes its EventBus on first arrival and forwards future events to this
daemon as ModuleEvent. Unknown class names are answered with an ErrorReport.


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| event_types | [string](#string) | repeated |  |






<a name="me-prexorjustin-prexorcloud-protocol-EventUnsubscribe"></a>

### EventUnsubscribe
Daemon drops interest in one or more event types previously registered.
On daemon disconnect the controller cleans up automatically; this message
is for live-unsubscribe inside a connected session.


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| event_types | [string](#string) | repeated |  |






<a name="me-prexorjustin-prexorcloud-protocol-ExtensionArtifact"></a>

### ExtensionArtifact



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| module_id | [string](#string) |  |  |
| extension_id | [string](#string) |  |  |
| variant_id | [string](#string) |  |  |
| file_name | [string](#string) |  |  |
| download_url | [string](#string) |  |  |
| sha256 | [string](#string) |  |  |
| install_path | [string](#string) |  |  |






<a name="me-prexorjustin-prexorcloud-protocol-FileEntry"></a>

### FileEntry
Single filetree entry. When summary=true, path identifies a directory that
exceeds summarize_threshold children — size_bytes is the recursive total
and child_count is the directly-contained child count.


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| path | [string](#string) |  | Path relative to the instance working directory |
| size_bytes | [int64](#int64) |  | File size (or recursive sum for summarized dirs) |
| is_dir | [bool](#bool) |  | True for directories |
| modified_at_ms | [int64](#int64) |  | Last-modified epoch milliseconds (0 if unavailable) |
| summary | [bool](#bool) |  | True = directory summary line (children not enumerated) |
| child_count | [int32](#int32) |  | For summary=true entries: number of directly-contained children |






<a name="me-prexorjustin-prexorcloud-protocol-Handshake"></a>

### Handshake
REQUIRED fields: node_id, version, protocol_version


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| node_id | [string](#string) |  | REQUIRED: unique node identifier |
| version | [string](#string) |  | REQUIRED: daemon software version (e.g. &#34;1.0.0&#34;) |
| total_memory_mb | [int64](#int64) |  | Total memory available on this node |
| available_cpus | [int32](#int32) |  | Number of CPU cores available |
| labels | [Handshake.LabelsEntry](#me-prexorjustin-prexorcloud-protocol-Handshake-LabelsEntry) | repeated | Operator-defined labels (region, tier, etc.) |
| running_instances | [RunningInstance](#me-prexorjustin-prexorcloud-protocol-RunningInstance) | repeated | Instances currently alive (for reconciliation) |
| advertise_address | [string](#string) |  | Routable IP/hostname. Empty = auto-detect from gRPC peer |
| host_info | [HostInfo](#me-prexorjustin-prexorcloud-protocol-HostInfo) |  | Hardware and OS info for observability |
| protocol_version | [int32](#int32) |  | REQUIRED: protocol version for compatibility check |






<a name="me-prexorjustin-prexorcloud-protocol-Handshake-LabelsEntry"></a>

### Handshake.LabelsEntry



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| key | [string](#string) |  |  |
| value | [string](#string) |  |  |






<a name="me-prexorjustin-prexorcloud-protocol-HandshakeAck"></a>

### HandshakeAck



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| session_id | [string](#string) |  | REQUIRED: unique session identifier |
| heartbeat_interval_ms | [int64](#int64) |  | How often daemon should send NodeStatus (default: 30000) |
| controller_api_port | [int32](#int32) |  | REST API port for JAR downloads |
| protocol_version | [int32](#int32) |  | Controller&#39;s protocol version |
| protocol_compatible | [bool](#bool) |  | false = daemon should disconnect and upgrade |






<a name="me-prexorjustin-prexorcloud-protocol-HostInfo"></a>

### HostInfo



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| os_name | [string](#string) |  | e.g. &#34;Linux&#34;, &#34;Windows 11&#34;, &#34;macOS 15.3&#34; |
| os_version | [string](#string) |  | e.g. &#34;6.1.0-18-amd64&#34;, &#34;10.0.26200&#34; |
| arch | [string](#string) |  | e.g. &#34;amd64&#34;, &#34;aarch64&#34; |
| cpu_model | [string](#string) |  | e.g. &#34;AMD EPYC 7543 32-Core Processor&#34; |
| cpu_physical_cores | [int32](#int32) |  |  |
| cpu_logical_cores | [int32](#int32) |  |  |
| cpu_max_freq_hz | [int64](#int64) |  | Max frequency in Hz (0 if unavailable) |
| java_version | [string](#string) |  | JVM version running on the daemon |
| java_vendor | [string](#string) |  | e.g. &#34;Eclipse Adoptium&#34;, &#34;GraalVM Community&#34;, &#34;Azul Systems, Inc.&#34; |
| java_runtime | [string](#string) |  | e.g. &#34;OpenJDK 64-Bit Server VM&#34;, &#34;GraalVM CE&#34; |
| java_gc | [string](#string) |  | Active GC: &#34;G1&#34;, &#34;ZGC&#34;, &#34;Shenandoah&#34;, &#34;Parallel&#34;, etc. |






<a name="me-prexorjustin-prexorcloud-protocol-InstanceFileContent"></a>

### InstanceFileContent



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| request_id | [string](#string) |  | REQUIRED: mirrors ReadInstanceFile.request_id |
| content | [bytes](#bytes) |  | UTF-8 bytes (caller treats as text); may be empty when error != &#34;&#34; |
| total_size_bytes | [int64](#int64) |  | Full file size on disk, so callers see &#34;truncated to X of Y&#34; |
| truncated | [bool](#bool) |  | True when max_bytes was hit |
| error | [string](#string) |  | &#34;&#34; on success; &#34;INSTANCE_NOT_FOUND&#34; | &#34;FILE_NOT_FOUND&#34; | &#34;NOT_REGULAR_FILE&#34; | &#34;FILE_UNREADABLE&#34; | &#34;PATH_OUTSIDE_INSTANCE&#34; |






<a name="me-prexorjustin-prexorcloud-protocol-InstanceFileTree"></a>

### InstanceFileTree



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| request_id | [string](#string) |  | REQUIRED: mirrors WalkInstanceFiles.request_id |
| entries | [FileEntry](#me-prexorjustin-prexorcloud-protocol-FileEntry) | repeated |  |
| truncated | [bool](#bool) |  | True when max_entries / max_depth was hit |
| error | [string](#string) |  | &#34;&#34; on success; &#34;INSTANCE_NOT_FOUND&#34; | &#34;DIR_UNREADABLE&#34; | other |






<a name="me-prexorjustin-prexorcloud-protocol-InstanceStatusUpdate"></a>

### InstanceStatusUpdate



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| instance_id | [string](#string) |  | REQUIRED |
| state | [InstanceState](#me-prexorjustin-prexorcloud-protocol-InstanceState) |  | REQUIRED |
| port | [int32](#int32) |  |  |
| player_count | [int32](#int32) |  | 0 = no players connected |
| uptime_ms | [int64](#int64) |  | Milliseconds since instance start |






<a name="me-prexorjustin-prexorcloud-protocol-JarCacheEntry"></a>

### JarCacheEntry



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| platform | [string](#string) |  |  |
| version | [string](#string) |  |  |
| jar_file | [string](#string) |  |  |
| size_bytes | [int64](#int64) |  |  |
| sha256 | [string](#string) |  |  |
| cached_at_ms | [int64](#int64) |  | Epoch milliseconds |






<a name="me-prexorjustin-prexorcloud-protocol-ModuleEvent"></a>

### ModuleEvent
Sent by the controller to forward a controller-bus CloudEvent to a daemon
that has subscribed to that event type. payload_json is the event serialized
via ObjectMappers.standard() (Jackson with registered modules).


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| event_type | [string](#string) |  | Fully-qualified Java class name of the CloudEvent |
| payload_json | [bytes](#bytes) |  | Jackson-serialized event payload |






<a name="me-prexorjustin-prexorcloud-protocol-ModuleInstall"></a>

### ModuleInstall
Sent by the controller to push a daemon-host platform module onto a daemon.
Payload is the raw module jar bytes plus an optional cosign / sig sidecar.
jar_bytes inlines the artifact for simplicity; if the shadowJar exceeds
ProtocolConstants.MAX_MESSAGE_SIZE, switch to chunked transfer (deferred).


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| module_id | [string](#string) |  | REQUIRED |
| version | [string](#string) |  | REQUIRED |
| sha256 | [string](#string) |  | REQUIRED: hex-encoded SHA-256 of jar_bytes |
| jar_bytes | [bytes](#bytes) |  | REQUIRED: raw module jar |
| signature_bytes | [bytes](#bytes) |  | Optional .sig sidecar or cosign bundle |
| signature_kind | [string](#string) |  | &#34;sig&#34; | &#34;cosign-bundle&#34; | &#34;&#34; (when signature_bytes is empty) |
| manifest_yaml | [string](#string) |  | Module manifest YAML (avoids re-extracting from jar) |
| is_upgrade | [bool](#bool) |  | true when replacing an existing version on the daemon |
| previous_version | [string](#string) |  | Set when is_upgrade=true; previous installed version |






<a name="me-prexorjustin-prexorcloud-protocol-ModuleStateUpdate"></a>

### ModuleStateUpdate
Daemon-side report of a module&#39;s lifecycle state. Sent on every transition
(INSTALLED -&gt; WAITING -&gt; ACTIVE -&gt; STOPPING / FAILED / UNLOADED) and as a
reconciliation snapshot on handshake. last_error is non-empty only when
state=FAILED.


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| module_id | [string](#string) |  |  |
| state | [string](#string) |  | INSTALLED | WAITING | ACTIVE | STOPPING | UNLOADED | FAILED |
| last_error | [string](#string) |  |  |
| updated_at_ms | [int64](#int64) |  | Epoch milliseconds of the transition |






<a name="me-prexorjustin-prexorcloud-protocol-ModuleUninstall"></a>

### ModuleUninstall
Sent by the controller to drop a daemon-host platform module on a daemon.


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| module_id | [string](#string) |  |  |






<a name="me-prexorjustin-prexorcloud-protocol-NodeStatus"></a>

### NodeStatus



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| cpu_usage | [double](#double) |  | 0.0 - 1.0 (fraction of total CPU) |
| total_memory_mb | [int64](#int64) |  |  |
| used_memory_mb | [int64](#int64) |  |  |
| free_disk_mb | [int64](#int64) |  |  |
| instance_count | [int32](#int32) |  |  |
| used_ports | [int32](#int32) | repeated |  |
| total_disk_mb | [int64](#int64) |  |  |






<a name="me-prexorjustin-prexorcloud-protocol-Ping"></a>

### Ping



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| sequence | [int64](#int64) |  | Incrementing sequence number. Daemon must respond with Pong(sequence). |






<a name="me-prexorjustin-prexorcloud-protocol-Pong"></a>

### Pong



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| sequence | [int64](#int64) |  | Must echo the sequence from the corresponding Ping |






<a name="me-prexorjustin-prexorcloud-protocol-PreWarmCache"></a>

### PreWarmCache
Sent by controller after handshake to let the daemon pre-warm caches
(JAR downloads, bootstrap artifacts, CDS archives) before any instance
is scheduled. This eliminates cold-start latency for the first instance.


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| entries | [PreWarmEntry](#me-prexorjustin-prexorcloud-protocol-PreWarmEntry) | repeated |  |






<a name="me-prexorjustin-prexorcloud-protocol-PreWarmEntry"></a>

### PreWarmEntry



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| platform | [string](#string) |  | e.g. PAPER, PURPUR |
| platform_version | [string](#string) |  | e.g. 1.21.4 |
| config_format | [ConfigFormat](#me-prexorjustin-prexorcloud-protocol-ConfigFormat) |  | Config template set |
| jar_file | [string](#string) |  | e.g. server.jar |
| download_url | [string](#string) |  | URL to fetch the jar from |
| sha256 | [string](#string) |  | Optional runtime jar hash for cache verification |






<a name="me-prexorjustin-prexorcloud-protocol-ReadInstanceFile"></a>

### ReadInstanceFile
Controller asks the daemon for the contents of a single file under the
instance working directory. Used by support workflows that need to embed
small text files (server.properties, logs/latest.log tail) into the
diagnostics bundle. The daemon caps max_bytes on top of the request&#39;s value
and refuses paths outside the instance working dir.


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| request_id | [string](#string) |  | REQUIRED: correlation id matched by InstanceFileContent.request_id |
| group | [string](#string) |  | REQUIRED |
| instance_id | [string](#string) |  | REQUIRED |
| path | [string](#string) |  | REQUIRED: relative path under the instance dir (forward slashes) |
| max_bytes | [int32](#int32) |  | Hard cap on bytes returned (0 = daemon default — typically 64 KiB) |
| tail | [bool](#bool) |  | When true, return the LAST max_bytes bytes instead of the first |






<a name="me-prexorjustin-prexorcloud-protocol-RequestCacheStatus"></a>

### RequestCacheStatus







<a name="me-prexorjustin-prexorcloud-protocol-RunningInstance"></a>

### RunningInstance



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| instance_id | [string](#string) |  |  |
| group | [string](#string) |  |  |
| port | [int32](#int32) |  |  |
| state | [InstanceState](#me-prexorjustin-prexorcloud-protocol-InstanceState) |  |  |






<a name="me-prexorjustin-prexorcloud-protocol-RuntimeArtifact"></a>

### RuntimeArtifact



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| jar_file | [string](#string) |  |  |
| download_url | [string](#string) |  |  |
| sha256 | [string](#string) |  |  |
| platform | [string](#string) |  |  |
| platform_version | [string](#string) |  |  |
| category | [InstanceCategory](#me-prexorjustin-prexorcloud-protocol-InstanceCategory) |  |  |
| config_format | [ConfigFormat](#me-prexorjustin-prexorcloud-protocol-ConfigFormat) |  |  |






<a name="me-prexorjustin-prexorcloud-protocol-RuntimeIsolation"></a>

### RuntimeIsolation



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| cpu_reservation | [double](#double) |  | Fraction of total node CPU reserved for this instance (0.0-1.0) |
| disk_reservation_mb | [int64](#int64) |  | Requested disk reservation used for runtime isolation hooks |






<a name="me-prexorjustin-prexorcloud-protocol-SendCommand"></a>

### SendCommand



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| instance_id | [string](#string) |  | REQUIRED |
| command | [string](#string) |  | REQUIRED: command string to send to server stdin |






<a name="me-prexorjustin-prexorcloud-protocol-ShutdownNode"></a>

### ShutdownNode



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| reason | [string](#string) |  | Human-readable reason (e.g. &#34;drain completed&#34;, &#34;operator request&#34;) |






<a name="me-prexorjustin-prexorcloud-protocol-ShutdownNodeAck"></a>

### ShutdownNodeAck
Sent by daemon to acknowledge a ShutdownNode request


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| running_instances | [int32](#int32) |  | Number of instances still running |
| estimated_drain_seconds | [int32](#int32) |  | Estimated time to gracefully stop all instances |






<a name="me-prexorjustin-prexorcloud-protocol-StartInstance"></a>

### StartInstance
REQUIRED fields: instance_id, group, port, jar_file


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| instance_id | [string](#string) |  | REQUIRED: unique instance identifier (e.g. &#34;lobby-1&#34;) |
| group | [string](#string) |  | REQUIRED: group this instance belongs to |
| port | [int32](#int32) |  | REQUIRED: port the server will bind to |
| memory_mb | [int32](#int32) |  | JVM heap size in MB (0 = use default 512) |
| jvm_args | [string](#string) | repeated | Additional JVM arguments |
| env | [StartInstance.EnvEntry](#me-prexorjustin-prexorcloud-protocol-StartInstance-EnvEntry) | repeated | Environment variables for the process |
| jar_file | [string](#string) |  | REQUIRED: server JAR filename |
| plugin_token | [string](#string) |  | Auth token for plugin ↔ controller communication |
| templates | [TemplateRef](#me-prexorjustin-prexorcloud-protocol-TemplateRef) | repeated | Templates to unpack (in order) |
| startup_timeout_seconds | [int32](#int32) |  | 0 = use default (60s) |
| shutdown_grace_seconds | [int32](#int32) |  | 0 = use default from daemon config |
| max_lifetime_seconds | [int32](#int32) |  | 0 = no limit |
| deployment_revision | [int32](#int32) |  | Deployment revision for rolling updates |
| static_instance | [bool](#bool) |  | If true, instance dir is preserved across restarts |
| protected_paths | [string](#string) | repeated | Paths not overwritten on template re-apply (static only) |
| category | [InstanceCategory](#me-prexorjustin-prexorcloud-protocol-InstanceCategory) |  | SERVER or PROXY |
| download_url | [string](#string) |  | URL to download JAR if not cached |
| platform | [string](#string) |  | e.g. &#34;PAPER&#34;, &#34;PURPUR&#34;, &#34;VELOCITY&#34; |
| platform_version | [string](#string) |  | e.g. &#34;1.21.4&#34; |
| max_players | [int32](#int32) |  | 0 = use default (100) |
| config_format | [ConfigFormat](#me-prexorjustin-prexorcloud-protocol-ConfigFormat) |  | Config template set to apply |
| composition_plan | [CompositionPlan](#me-prexorjustin-prexorcloud-protocol-CompositionPlan) |  | Controller-resolved runtime/templates/extensions plan |
| isolation | [RuntimeIsolation](#me-prexorjustin-prexorcloud-protocol-RuntimeIsolation) |  | Runtime isolation hints for daemon-local enforcement hooks |






<a name="me-prexorjustin-prexorcloud-protocol-StartInstance-EnvEntry"></a>

### StartInstance.EnvEntry



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| key | [string](#string) |  |  |
| value | [string](#string) |  |  |






<a name="me-prexorjustin-prexorcloud-protocol-StartInstanceAck"></a>

### StartInstanceAck
Sent by daemon to confirm receipt and outcome of a StartInstance command


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| instance_id | [string](#string) |  | Mirrors StartInstance.instance_id |
| accepted | [bool](#bool) |  | true = daemon accepted and began launch; false = rejected |
| error_message | [string](#string) |  | Non-empty when accepted=false |
| plan_hash | [string](#string) |  | Mirrors CompositionPlan.plan_hash when present |
| stage | [StartPreparationStage](#me-prexorjustin-prexorcloud-protocol-StartPreparationStage) |  | Last completed or failed preparation stage |
| error_code | [string](#string) |  | Machine-readable failure code when accepted=false |
| failure_disposition | [StartFailureDisposition](#me-prexorjustin-prexorcloud-protocol-StartFailureDisposition) |  | Permanent vs transient rejection classification |
| retry_after_seconds | [int32](#int32) |  | Suggested controller retry delay when failure_disposition=TRANSIENT |






<a name="me-prexorjustin-prexorcloud-protocol-StopInstance"></a>

### StopInstance



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| instance_id | [string](#string) |  | REQUIRED |
| force | [bool](#bool) |  | true = SIGKILL, false = graceful stop command |






<a name="me-prexorjustin-prexorcloud-protocol-StopInstanceAck"></a>

### StopInstanceAck
Sent by daemon to confirm receipt and outcome of a StopInstance command


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| instance_id | [string](#string) |  | Mirrors StopInstance.instance_id |
| accepted | [bool](#bool) |  | true = daemon accepted and began stop; false = rejected |
| error_message | [string](#string) |  | Non-empty when accepted=false |






<a name="me-prexorjustin-prexorcloud-protocol-TemplateCacheEntry"></a>

### TemplateCacheEntry



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| name | [string](#string) |  |  |
| hash | [string](#string) |  |  |
| size_bytes | [int64](#int64) |  |  |
| last_used_ms | [int64](#int64) |  | Epoch milliseconds (0 = never used) |






<a name="me-prexorjustin-prexorcloud-protocol-TemplateData"></a>

### TemplateData



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| template_name | [string](#string) |  |  |
| hash | [string](#string) |  | SHA-256 hash of the tar.gz content |
| tar_gz | [bytes](#bytes) |  | Template archive |






<a name="me-prexorjustin-prexorcloud-protocol-TemplateRef"></a>

### TemplateRef



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| name | [string](#string) |  |  |
| hash | [string](#string) |  |  |






<a name="me-prexorjustin-prexorcloud-protocol-TemplateRequest"></a>

### TemplateRequest



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| template_name | [string](#string) |  | REQUIRED: which template to fetch |
| known_hash | [string](#string) |  | Hash of locally cached version (empty = no cache) |






<a name="me-prexorjustin-prexorcloud-protocol-TemplateUpToDate"></a>

### TemplateUpToDate



| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| template_name | [string](#string) |  | Response to TemplateRequest when known_hash matches |






<a name="me-prexorjustin-prexorcloud-protocol-WalkInstanceFiles"></a>

### WalkInstanceFiles
Controller asks the daemon for a structure-only listing of an instance
working directory (path &#43; size &#43; isDir &#43; mtime; no file contents). The
daemon caps depth/entry count and summarizes any directory that exceeds
summarize_threshold children into a single FileEntry with summary=true
so a Minecraft world/region folder (30k&#43; leaves) doesn&#39;t blow the size
budget.


| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| request_id | [string](#string) |  | REQUIRED: correlation id matched by InstanceFileTree.request_id |
| group | [string](#string) |  | REQUIRED |
| instance_id | [string](#string) |  | REQUIRED |
| max_entries | [int32](#int32) |  | Hard cap on total entries returned (0 = daemon default) |
| max_depth | [int32](#int32) |  | Hard cap on directory depth (0 = daemon default) |
| summarize_threshold | [int32](#int32) |  | Dirs with &gt; threshold children are summarized (0 = daemon default) |





 


<a name="me-prexorjustin-prexorcloud-protocol-ConfigFormat"></a>

### ConfigFormat


| Name | Number | Description |
| ---- | ------ | ----------- |
| CONFIG_FORMAT_UNSPECIFIED | 0 |  |
| PAPER | 1 |  |
| SPIGOT | 2 |  |
| VELOCITY | 3 |  |
| BUNGEECORD | 4 |  |



<a name="me-prexorjustin-prexorcloud-protocol-InstanceCategory"></a>

### InstanceCategory


| Name | Number | Description |
| ---- | ------ | ----------- |
| INSTANCE_CATEGORY_UNSPECIFIED | 0 |  |
| SERVER | 1 |  |
| PROXY | 2 |  |



<a name="me-prexorjustin-prexorcloud-protocol-InstanceState"></a>

### InstanceState


| Name | Number | Description |
| ---- | ------ | ----------- |
| INSTANCE_STATE_UNSPECIFIED | 0 |  |
| SCHEDULED | 1 |  |
| PREPARING | 2 |  |
| STARTING | 3 |  |
| RUNNING | 4 |  |
| STOPPING | 5 |  |
| STOPPED | 6 |  |
| CRASHED | 7 |  |
| DRAINING | 8 |  |



<a name="me-prexorjustin-prexorcloud-protocol-StartFailureDisposition"></a>

### StartFailureDisposition


| Name | Number | Description |
| ---- | ------ | ----------- |
| START_FAILURE_DISPOSITION_UNSPECIFIED | 0 |  |
| PERMANENT | 1 |  |
| TRANSIENT | 2 |  |



<a name="me-prexorjustin-prexorcloud-protocol-StartPreparationStage"></a>

### StartPreparationStage


| Name | Number | Description |
| ---- | ------ | ----------- |
| START_PREPARATION_STAGE_UNSPECIFIED | 0 |  |
| VALIDATION | 1 |  |
| TEMPLATE_APPLY | 2 |  |
| RUNTIME_PROVISION | 3 |  |
| EXTENSION_PROVISION | 4 |  |
| BOOTSTRAP_WARMUP | 5 |  |
| VARIABLE_SUBSTITUTION | 6 |  |
| CONFIG_PATCH | 7 |  |
| PROCESS_START | 8 |  |


 

 


<a name="me-prexorjustin-prexorcloud-protocol-DaemonService"></a>

### DaemonService


| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| Connect | [DaemonMessage](#me-prexorjustin-prexorcloud-protocol-DaemonMessage) stream | [ControllerMessage](#me-prexorjustin-prexorcloud-protocol-ControllerMessage) stream |  |

 



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

