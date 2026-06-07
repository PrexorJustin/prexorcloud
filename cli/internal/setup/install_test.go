package setup

import "testing"

func TestDistroIsLike(t *testing.T) {
	d := Distro{ID: "linuxmint", Like: []string{"ubuntu", "debian"}}
	for _, tag := range []string{"linuxmint", "ubuntu", "debian", "Ubuntu", "DEBIAN"} {
		if !d.IsLike(tag) {
			t.Errorf("expected %q to match Mint distro", tag)
		}
	}
	if d.IsLike("arch") {
		t.Errorf("did not expect Mint to match arch")
	}
}

func TestMongoDBNative(t *testing.T) {
	cases := []struct {
		name string
		d    Distro
		want bool
	}{
		{"ubuntu", Distro{ID: "ubuntu", PackageMgr: "apt"}, true},
		{"debian", Distro{ID: "debian", PackageMgr: "apt"}, true},
		{"linuxmint", Distro{ID: "linuxmint", Like: []string{"ubuntu"}, PackageMgr: "apt"}, true},
		{"pop", Distro{ID: "pop", Like: []string{"ubuntu", "debian"}, PackageMgr: "apt"}, true},
		{"kali", Distro{ID: "kali", Like: []string{"debian"}, PackageMgr: "apt"}, true},
		{"rhel", Distro{ID: "rhel", Like: []string{"fedora"}, PackageMgr: "dnf"}, true},
		{"rocky", Distro{ID: "rocky", Like: []string{"rhel", "centos", "fedora"}, PackageMgr: "dnf"}, true},
		{"oracle", Distro{ID: "ol", Like: []string{"fedora"}, PackageMgr: "dnf"}, false}, // ID_LIKE only mentions fedora
		{"fedora", Distro{ID: "fedora", PackageMgr: "dnf"}, false},
		{"amzn", Distro{ID: "amzn", PackageMgr: "dnf"}, false},
		{"arch", Distro{ID: "arch", PackageMgr: "pacman"}, false},
		{"manjaro", Distro{ID: "manjaro", Like: []string{"arch"}, PackageMgr: "pacman"}, false},
		{"opensuse", Distro{ID: "opensuse-tumbleweed", Like: []string{"opensuse", "suse"}, PackageMgr: "zypper"}, false},
		{"alpine", Distro{ID: "alpine", PackageMgr: "apk"}, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := MongoDBNative(c.d); got != c.want {
				t.Errorf("MongoDBNative(%s) = %v, want %v", c.name, got, c.want)
			}
		})
	}
}

func TestMongoDBAptVendor(t *testing.T) {
	cases := []struct {
		d    Distro
		want string
	}{
		{Distro{ID: "ubuntu"}, "ubuntu"},
		{Distro{ID: "debian"}, "debian"},
		{Distro{ID: "linuxmint", Like: []string{"ubuntu"}}, "ubuntu"},
		{Distro{ID: "pop", Like: []string{"ubuntu", "debian"}}, "ubuntu"},
		{Distro{ID: "elementary", Like: []string{"ubuntu", "debian"}}, "ubuntu"},
		{Distro{ID: "kali", Like: []string{"debian"}}, "debian"},
		{Distro{ID: "raspbian", Like: []string{"debian"}}, "debian"},
	}
	for _, c := range cases {
		if got := mongoDBAptVendor(c.d); got != c.want {
			t.Errorf("mongoDBAptVendor(id=%s like=%v) = %s, want %s", c.d.ID, c.d.Like, got, c.want)
		}
	}
}

func TestMongoDBCodename(t *testing.T) {
	cases := []struct {
		name    string
		d       Distro
		vendor  string
		want    string
		wantErr bool
	}{
		{"ubuntu jammy", Distro{Codename: "jammy"}, "ubuntu", "jammy", false},
		{"ubuntu noble", Distro{Codename: "noble"}, "ubuntu", "noble", false},
		{"ubuntu questing remap", Distro{Codename: "questing"}, "ubuntu", "noble", false},
		{"ubuntu plucky remap", Distro{Codename: "plucky"}, "ubuntu", "noble", false},
		{"debian bookworm", Distro{Codename: "bookworm"}, "debian", "bookworm", false},
		{"debian trixie remap", Distro{Codename: "trixie"}, "debian", "bookworm", false},
		{"missing codename", Distro{}, "ubuntu", "", true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, err := mongoDBCodename(c.d, c.vendor)
			if c.wantErr {
				if err == nil {
					t.Errorf("expected error, got %q", got)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if got != c.want {
				t.Errorf("got %q, want %q", got, c.want)
			}
		})
	}
}

func TestRhelMajor(t *testing.T) {
	cases := []struct {
		in, want string
	}{
		{"9", "9"},
		{"9.4", "9"},
		{"8.10", "8"},
		{"", ""},
		{"  9 ", "9"},
	}
	for _, c := range cases {
		if got := rhelMajor(Distro{VersionID: c.in}); got != c.want {
			t.Errorf("rhelMajor(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}
