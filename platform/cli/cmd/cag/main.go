package main

import (
	"context"
	"fmt"
	"os"

	"github.com/xor777/denza-gateway/cli/internal/cag"
)

func main() {
	if os.Getenv("CAG_ASKPASS_MODE") == "1" {
		fmt.Println(os.Getenv("CAG_ASKPASS_SECRET"))
		return
	}
	if err := cag.New().Run(context.Background(), os.Args[1:]); err != nil {
		fmt.Fprintf(os.Stderr, "cag: %v\n", err)
		os.Exit(1)
	}
}
