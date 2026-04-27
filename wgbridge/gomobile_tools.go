//go:build tools_only
// +build tools_only

// This file is never compiled (the tools_only build tag isn't passed
// anywhere) but its presence forces `go mod tidy` to keep
// golang.org/x/mobile/bind in go.mod. `gomobile bind` needs that import
// path resolvable from the module graph; without this stub it errors
// with "no Go package in golang.org/x/mobile/bind" before any of our
// code is even processed.
package wgbridge

import _ "golang.org/x/mobile/bind"
