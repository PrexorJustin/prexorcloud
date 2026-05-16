package setup

import (
	"strings"
	"testing"
)

func TestRenderControllerUnitUsesOnlySelectedLocalDependencies(t *testing.T) {
	unit := renderControllerUnit(
		"/opt/prexorcloud/controller",
		"/opt/prexorcloud/jre",
		ControllerServiceOptions{LocalMongo: true, LocalRedis: false},
	)

	if !strings.Contains(unit, "After=network.target mongod.service") {
		t.Fatalf("controller unit missing mongod dependency: %s", unit)
	}
	if !strings.Contains(unit, "Wants=mongod.service") {
		t.Fatalf("controller unit missing mongod wants: %s", unit)
	}
	if strings.Contains(unit, "redis.service") {
		t.Fatalf("controller unit unexpectedly references redis.service: %s", unit)
	}
}

func TestRenderControllerUnitOmitsLocalServiceDependenciesForRemoteStores(t *testing.T) {
	unit := renderControllerUnit(
		"/opt/prexorcloud/controller",
		"/opt/prexorcloud/jre",
		ControllerServiceOptions{},
	)

	if !strings.Contains(unit, "After=network.target") {
		t.Fatalf("controller unit missing base network dependency: %s", unit)
	}
	if strings.Contains(unit, "Wants=") {
		t.Fatalf("controller unit unexpectedly has Wants line: %s", unit)
	}
	if strings.Contains(unit, "mongod.service") || strings.Contains(unit, "redis.service") {
		t.Fatalf("controller unit unexpectedly references local data services: %s", unit)
	}
}
