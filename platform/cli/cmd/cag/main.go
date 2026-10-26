package main

import (
	"context"
	"fmt"
	"os"

	"github.com/xor777/denza-lab/platform/cli/internal/cag"
)

func main() {
	if secret, ok := cag.AskpassSecret(os.Args[1:], os.Getenv); ok {
		fmt.Println(secret)
		return
	}
	if err := cag.New().Run(context.Background(), os.Args[1:]); err != nil {
		fmt.Fprintf(os.Stderr, "cag: %v\n", err)
		os.Exit(1)
	}
}
