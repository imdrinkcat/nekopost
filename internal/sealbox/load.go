package sealbox

import (
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

const (
	credName      = "nekopost-kek"
	encryptedCred = "/etc/credstore.encrypted/nekopost-kek"
	dockerSecret  = "/run/secrets/nekopost_kek"
)

// Location is where LoadMaterial looks. The service process uses the three
// file paths. Admin commands may also decrypt the systemd credential into
// memory when the service is not running.
type Location struct {
	File         string // NEKOPOST_KEK_FILE
	AllowSystemd bool
}

// Material is the current KEK and, during rotation, the previous one.
type Material struct {
	Current  []byte
	Previous []byte
}

// LoadMaterial reads the KEK. It never logs the bytes it reads.
func LoadMaterial(loc Location) (Material, error) {
	var paths []string
	if loc.File != "" {
		paths = append(paths, loc.File)
	} else if p := os.Getenv("NEKOPOST_KEK_FILE"); p != "" {
		paths = append(paths, p)
	}
	if dir := os.Getenv("CREDENTIALS_DIRECTORY"); dir != "" {
		paths = append(paths, filepath.Join(dir, credName))
	}
	paths = append(paths, dockerSecret)

	var missing []string
	for _, p := range paths {
		b, err := os.ReadFile(p)
		if err != nil {
			if os.IsNotExist(err) {
				missing = append(missing, p)
				continue
			}
			return Material{}, fmt.Errorf("读取主密钥失败: %w", err)
		}
		cur, prev, err := ParseKeyMaterial(b)
		if err != nil {
			return Material{}, err
		}
		return Material{Current: cur, Previous: prev}, nil
	}
	if loc.AllowSystemd {
		if st, err := os.Stat(encryptedCred); err == nil && !st.IsDir() {
			b, err := DecryptSystemd(encryptedCred)
			if err != nil {
				return Material{}, err
			}
			cur, prev, err := ParseKeyMaterial(b)
			if err != nil {
				return Material{}, err
			}
			return Material{Current: cur, Previous: prev}, nil
		}
	}
	return Material{}, fmt.Errorf("未找到主密钥：请运行 sudo nekopost keys init（见 docs/encryption.md）")
}

// EncryptSystemd writes plaintext into a systemd encrypted credential.
// plaintext is not written to disk by this process.
func EncryptSystemd(dest string, plain []byte) error {
	cmd := exec.Command("systemd-creds", "encrypt", "--name="+credName, "-", dest)
	cmd.Stdin = bytes.NewReader(plain)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("systemd-creds encrypt 失败: %s", trimOut(out))
	}
	return nil
}

// DecryptSystemd reads an encrypted credential into memory.
func DecryptSystemd(path string) ([]byte, error) {
	cmd := exec.Command("systemd-creds", "decrypt", path, "-")
	out, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("systemd-creds decrypt 失败")
	}
	return out, nil
}

// SaveMaterial writes the current key, and during rotation the previous one,
// back to the place LoadMaterial reads. It does not print the key.
func SaveMaterial(current, previous []byte) error {
	payload := FormatKeys(current, previous)
	if _, err := os.Stat(encryptedCred); err == nil {
		return EncryptSystemd(encryptedCred, payload)
	}
	if p := os.Getenv("NEKOPOST_KEK_FILE"); p != "" {
		return os.WriteFile(p, payload, 0o600)
	}
	if _, err := os.Stat(dockerSecret); err == nil {
		if len(previous) == 32 {
			return os.WriteFile(dockerSecret, payload, 0o400)
		}
		return os.WriteFile(dockerSecret, current, 0o400)
	}
	return fmt.Errorf("没有可写的主密钥位置")
}

func trimOut(b []byte) string {
	s := string(bytes.TrimSpace(b))
	if len(s) > 200 {
		s = s[:200]
	}
	return s
}
