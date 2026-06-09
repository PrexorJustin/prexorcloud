package cmd

import (
	"errors"
	"fmt"
	"time"

	"github.com/prexorcloud/prexorctl/internal/setup"
	"github.com/prexorcloud/prexorctl/internal/tui"
)

func ensureJava() error {
	return ensureDependency("Java 25",
		func() string { return setup.DetectJava(setup.ManagedJREPath) },
		func() error {
			ok, err := confirmSetup(
				"Java 25 is not installed. Install automatically? (Eclipse Temurin JRE 25)",
				true,
			)
			if err != nil {
				return err
			}
			if !ok {
				return errors.New("Java 25 is required. Exiting setup")
			}
			return tui.SpinWith("Downloading Eclipse Temurin JRE 25...", setup.InstallJRE)
		},
	)
}

// resolveMongoDB asks the user whether MongoDB should be installed locally
// on this host or an existing remote instance should be reused, runs the
// chosen path and returns the URI that the controller should use.
func resolveMongoDB(installMode string) (string, bool, error) {
	source, err := promptDependencySource("MongoDB", setupControllerMongoMode, "local")
	if err != nil {
		return "", false, err
	}
	if source == "local" {
		if installMode == installModeCompose {
			uri := "mongodb://mongo:27017"
			fmt.Printf("  %s  %-18s %s\n",
				styleSetupOK.Render("✓"),
				"MongoDB",
				styleSetupDim.Render("compose-managed local service: "+uri),
			)
			return uri, true, nil
		}
		// If the host distro has no native MongoDB package, fall through to the
		// remote-URI path instead of forcing a doomed install attempt. The user
		// can either point at an existing MongoDB or re-run with --install-mode=compose.
		distro, err := setup.DetectDistro()
		if err != nil {
			return "", false, err
		}
		if !setup.MongoDBNative(distro) {
			fmt.Printf("  %s  %-18s %s\n",
				styleSetupWarn.Render("!"),
				"MongoDB",
				styleSetupDim.Render(fmt.Sprintf("no native MongoDB package for %s — provide a remote URI, or re-run with --install-mode=compose", distro.ID)),
			)
		} else {
			if err := ensureDependency("MongoDB",
				setup.DetectMongoDB,
				func() error {
					ok, err := confirmSetup(
						"MongoDB is not installed. Install automatically? (MongoDB 8.0 via official repo)",
						true,
					)
					if err != nil {
						return err
					}
					if !ok {
						return errors.New("MongoDB is required. Exiting setup")
					}
					return tui.SpinWith("Installing MongoDB 8.0...", func() error {
						return setup.InstallMongoDB(distro)
					})
				},
			); err != nil {
				return "", false, err
			}
			uri := "mongodb://localhost:27017"
			if err := setup.DialTCPRetry("127.0.0.1:27017", 15*time.Second); err != nil {
				return "", false, fmt.Errorf("MongoDB was installed but is not reachable on 127.0.0.1:27017: %w", err)
			}
			return uri, true, nil
		}
	}

	uri, err := promptRemoteURI("MongoDB", "mongodb://host:27017", setupControllerMongoURI)
	if err != nil {
		return "", false, err
	}
	fmt.Printf("  %s  %-18s %s\n",
		styleSetupOK.Render("✓"),
		"MongoDB",
		styleSetupDim.Render("remote: "+uri),
	)
	return uri, false, nil
}

// resolveRedis asks the user whether Redis should be installed locally or
// reused, runs the chosen path and returns the resulting URI. When a remote
// instance is chosen the cluster is probed so the user sees whether they
// are joining an existing cluster or starting a fresh one.
func resolveRedis(installMode string) (string, bool, error) {
	source, err := promptDependencySource("Redis", setupControllerRedisMode, "local")
	if err != nil {
		return "", false, err
	}
	if source == "local" {
		if installMode == installModeCompose {
			uri := "redis://redis:6379"
			fmt.Printf("  %s  %-18s %s\n",
				styleSetupOK.Render("✓"),
				"Redis",
				styleSetupDim.Render("compose-managed local service: "+uri),
			)
			return uri, true, nil
		}
		if err := ensureDependency("Redis",
			setup.DetectRedis,
			func() error {
				ok, err := confirmSetup("Redis is not installed. Install automatically?", true)
				if err != nil {
					return err
				}
				if !ok {
					return errors.New("Redis is required. Exiting setup")
				}
				distro, err := setup.DetectDistro()
				if err != nil {
					return err
				}
				return tui.SpinWith("Installing Redis...", func() error {
					return setup.InstallRedis(distro)
				})
			},
		); err != nil {
			return "", false, err
		}
		uri := "redis://localhost:6379"
		if err := setup.DialTCPRetry("127.0.0.1:6379", 15*time.Second); err != nil {
			return "", false, fmt.Errorf("Redis was installed but is not reachable on 127.0.0.1:6379: %w", err)
		}
		return uri, true, nil
	}

	uri, err := promptRemoteURI("Redis", "redis://host:6379", setupControllerRedisURI)
	if err != nil {
		return "", false, err
	}

	probe, probeErr := setup.ProbeRedisCluster(uri)
	if probeErr != nil {
		fmt.Printf("  %s  %-18s %s\n",
			styleSetupErr.Render("!"),
			"Redis probe",
			styleSetupErr.Render(probeErr.Error()),
		)
	} else {
		var msg string
		if probe.NodeOwners == 0 && probe.GroupLeases == 0 {
			msg = "empty — this controller will be the first in the cluster"
		} else {
			msg = fmt.Sprintf("cluster has %d node owner(s), %d group lease(s) — joining existing cluster",
				probe.NodeOwners, probe.GroupLeases)
		}
		fmt.Printf("  %s  %-18s %s\n",
			styleSetupOK.Render("✓"),
			"Redis probe",
			styleSetupDim.Render(msg),
		)
	}

	fmt.Printf("  %s  %-18s %s\n",
		styleSetupOK.Render("✓"),
		"Redis",
		styleSetupDim.Render("remote: "+uri),
	)
	return uri, false, nil
}
