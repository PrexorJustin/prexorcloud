package api

// OverviewResponse is the response from GET /api/v1/overview.
type OverviewResponse struct {
	NodeCount     int `json:"nodeCount"`
	InstanceCount int `json:"instanceCount"`
	PlayerCount   int `json:"playerCount"`
	GroupCount    int `json:"groupCount"`
}

// CatalogEntry is a flattened catalog entry from GET /api/v1/catalog — one row
// per platform version.
type CatalogEntry struct {
	Platform     string `json:"platform"`
	Category     string `json:"category"`
	ConfigFormat string `json:"configFormat"`
	Version      string `json:"version"`
	DownloadURL  string `json:"downloadUrl"`
	SHA256       string `json:"sha256"`
	Recommended  bool   `json:"recommended"`
}

// NodeResponse is a node entry from GET /api/v1/nodes.
type NodeResponse struct {
	NodeID         string             `json:"nodeId"`
	ID             string             `json:"id"`
	Type           string             `json:"type"`
	Status         string             `json:"status"`
	CPUUsage       float64            `json:"cpuUsage"`
	UsedMemoryMB   float64            `json:"usedMemoryMb"`
	TotalMemoryMB  float64            `json:"totalMemoryMb"`
	FreeDiskMB     float64            `json:"freeDiskMb"`
	InstanceCount  int                `json:"instanceCount"`
	ConnectedSince string             `json:"connectedSince"`
	Instances      []InstanceResponse `json:"instances,omitempty"`
}

// InstanceResponse is a server instance entry.
type InstanceResponse struct {
	ID                 string `json:"id"`
	Group              string `json:"group"`
	Node               string `json:"node"`
	State              string `json:"state"`
	Port               int    `json:"port"`
	PlayerCount        int    `json:"playerCount"`
	UptimeMs           int64  `json:"uptimeMs"`
	StartedAt          string `json:"startedAt"`
	DeploymentRevision int    `json:"deploymentRevision"`
}

// GroupResponse is a server group entry.
type GroupResponse struct {
	Name             string `json:"name"`
	Platform         string `json:"platform"`
	PlatformVersion  string `json:"platformVersion"`
	ScalingMode      string `json:"scalingMode"`
	MinInstances     int    `json:"minInstances"`
	MaxInstances     int    `json:"maxInstances"`
	MaxPlayers       int    `json:"maxPlayers"`
	RunningInstances int    `json:"runningInstances"`
	TotalPlayers     int    `json:"totalPlayers"`
	Maintenance      bool   `json:"maintenance"`
	Static           bool   `json:"static"`
}

// CrashResponse is a crash report entry.
type CrashResponse struct {
	ID             string   `json:"id"`
	InstanceID     string   `json:"instanceId"`
	Group          string   `json:"group"`
	NodeID         string   `json:"nodeId"`
	ExitCode       int      `json:"exitCode"`
	Classification string   `json:"classification"`
	LogTail        []string `json:"logTail"`
	UptimeMs       int64    `json:"uptimeMs"`
	Timestamp      string   `json:"timestamp"`
}

// LoginResponse is the response from POST /api/v1/auth/login.
type LoginResponse struct {
	Token string `json:"token"`
}

// VersionResponse is the response from GET /api/v1/system/version.
type VersionResponse struct {
	Version   string `json:"version"`
	BuildDate string `json:"buildDate,omitempty"`
	GoVersion string `json:"goVersion,omitempty"`
}

// TokenResponse is a join token entry.
type TokenResponse struct {
	TokenID        string `json:"tokenId"`
	NodeID         string `json:"nodeId"`
	ExpiresAtEpoch int64  `json:"expiresAtEpochMs"`
	Expired        bool   `json:"expired"`
	JoinToken      string `json:"joinToken,omitempty"`
}

// UserResponse is a user entry.
type UserResponse struct {
	Username      string   `json:"username"`
	Role          string   `json:"role"`
	Permissions   []string `json:"permissions"`
	MinecraftUUID string   `json:"minecraftUuid,omitempty"`
	MinecraftName string   `json:"minecraftName,omitempty"`
}

// ModuleResponse is a module entry.
type ModuleResponse struct {
	Name    string `json:"name"`
	Version string `json:"version"`
	State   string `json:"state"`
}

// TemplateResponse is a template entry.
type TemplateResponse struct {
	Name        string `json:"name"`
	Description string `json:"description,omitempty"`
	Platform    string `json:"platform,omitempty"`
	Hash        string `json:"hash"`
	SizeBytes   int64  `json:"sizeBytes"`
}

// TemplateVersionResponse is a template version entry.
type TemplateVersionResponse struct {
	Hash      string `json:"hash"`
	SizeBytes int64  `json:"sizeBytes"`
	CreatedAt string `json:"createdAt"`
}
