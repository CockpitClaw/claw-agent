package main

import (
	"encoding/base64"
)

func base64Decode(s string) ([]byte, error) {
	return base64.StdEncoding.DecodeString(s)
}

func base64Encode(b []byte) string {
	return base64.StdEncoding.EncodeToString(b)
}
