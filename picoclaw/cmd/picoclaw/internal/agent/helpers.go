package agent

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/ergochat/readline"

	"github.com/sipeed/picoclaw/cmd/picoclaw/internal"
	"github.com/sipeed/picoclaw/pkg/agent"
	"github.com/sipeed/picoclaw/pkg/bus"
	"github.com/sipeed/picoclaw/pkg/logger"
	runtimeevents "github.com/sipeed/picoclaw/pkg/events"
	"github.com/sipeed/picoclaw/pkg/providers"
)

func agentCmd(message, sessionKey, model string, debug bool) error {
	if sessionKey == "" {
		sessionKey = "cli:default"
	}

	cfg, err := internal.LoadConfig()
	if err != nil {
		return fmt.Errorf("error loading config: %w", err)
	}

	logger.ConfigureFromEnv()

	if debug {
		logger.SetLevel(logger.DEBUG)
		fmt.Println("🔍 Debug mode enabled")
	}

	if model != "" {
		cfg.Agents.Defaults.ModelName = model
	}

	provider, modelID, err := providers.CreateProvider(cfg)
	if err != nil {
		return fmt.Errorf("error creating provider: %w", err)
	}

	// Use the resolved model ID from provider creation
	if modelID != "" {
		cfg.Agents.Defaults.ModelName = modelID
	}

	msgBus := bus.NewMessageBus()
	defer msgBus.Close()
	agentLoop := agent.NewAgentLoop(cfg, msgBus, provider)
	defer agentLoop.Close()

	// Print agent startup info (only for interactive mode)
	startupInfo := agentLoop.GetStartupInfo()
	toolsInfo, ok := startupInfo["tools"].(map[string]any)
	if !ok {
		toolsInfo = nil
	}
	skillsInfo, ok := startupInfo["skills"].(map[string]any)
	if !ok {
		skillsInfo = nil
	}
	logFields := map[string]any{}
	if toolsInfo != nil {
		logFields["tools_count"] = toolsInfo["count"]
	}
	if skillsInfo != nil {
		logFields["skills_total"] = skillsInfo["total"]
		logFields["skills_available"] = skillsInfo["available"]
	}
	logger.InfoCF("agent", "Agent initialized", logFields)

	if message != "" {
		ctx := context.Background()
		sub := subscribeProgress(ctx, agentLoop)
		if sub != nil {
			defer sub.Close()
		}
		response, err := agentLoop.ProcessDirect(ctx, message, sessionKey)
		if err != nil {
			return fmt.Errorf("error processing message: %w", err)
		}
		fmt.Printf("\n%s %s\n", internal.Logo, response)
		return nil
	}

	fmt.Printf("%s Interactive mode (Ctrl+C to exit)\n\n", internal.Logo)
	interactiveMode(agentLoop, sessionKey)

	return nil
}

// subscribeProgress subscribes to agent runtime events and prints short progress
// lines so the user can see what the agent is doing in interactive mode.
func subscribeProgress(ctx context.Context, agentLoop *agent.AgentLoop) runtimeevents.Subscription {
	ch := agentLoop.RuntimeEvents()
	if ch == nil {
		return nil
	}
	filtered := ch.OfKind(
		runtimeevents.KindAgentLLMRequest,
		runtimeevents.KindAgentToolExecStart,
		runtimeevents.KindAgentToolExecEnd,
		runtimeevents.KindAgentTurnEnd,
		runtimeevents.KindAgentError,
	)
	sub, err := filtered.Subscribe(ctx, runtimeevents.SubscribeOptions{
		Name:        "cli-progress",
		Buffer:      64,
		Concurrency: runtimeevents.Locked,
	}, func(_ context.Context, evt runtimeevents.Event) error {
		switch evt.Kind {
		case runtimeevents.KindAgentLLMRequest:
			fmt.Print("  🤔 LLM 推理中...\r")
		case runtimeevents.KindAgentToolExecStart:
			if p, ok := evt.Payload.(agent.ToolExecStartPayload); ok {
				fmt.Printf("  🔧 %s ...\n", p.Tool)
			}
		case runtimeevents.KindAgentToolExecEnd:
			if p, ok := evt.Payload.(agent.ToolExecEndPayload); ok {
				if p.IsError {
					fmt.Printf("  ❌ %s 失败 (%dms)\n", p.Tool, p.Duration.Milliseconds())
				} else {
					fmt.Printf("  ✅ %s (%dms)\n", p.Tool, p.Duration.Milliseconds())
				}
			}
		case runtimeevents.KindAgentTurnEnd:
			fmt.Print("  ✅ 完成\n")
		case runtimeevents.KindAgentError:
			if p, ok := evt.Payload.(agent.ErrorPayload); ok {
				fmt.Printf("  ❌ 错误: %s\n", p.Message)
			}
		}
		return nil
	})
	if err != nil {
		logger.WarnCF("agent", "Failed to subscribe progress events", map[string]any{"error": err.Error()})
		return nil
	}
	return sub
}

func interactiveMode(agentLoop *agent.AgentLoop, sessionKey string) {
	prompt := fmt.Sprintf("%s You: ", internal.Logo)

	rl, err := readline.NewEx(&readline.Config{
		Prompt:          prompt,
		HistoryFile:     filepath.Join(os.TempDir(), ".picoclaw_history"),
		HistoryLimit:    100,
		InterruptPrompt: "^C",
		EOFPrompt:       "exit",
	})
	if err != nil {
		fmt.Printf("Error initializing readline: %v\n", err)
		fmt.Println("Falling back to simple input mode...")
		simpleInteractiveMode(agentLoop, sessionKey)
		return
	}
	defer rl.Close()

	ctx := context.Background()
	sub := subscribeProgress(ctx, agentLoop)
	if sub != nil {
		defer sub.Close()
	}

	for {
		line, err := rl.Readline()
		if err != nil {
			if err == readline.ErrInterrupt || err == io.EOF {
				fmt.Println("\nGoodbye!")
				return
			}
			fmt.Printf("Error reading input: %v\n", err)
			continue
		}

		input := strings.TrimSpace(line)
		if input == "" {
			continue
		}

		if input == "exit" || input == "quit" {
			fmt.Println("Goodbye!")
			return
		}

		response, err := agentLoop.ProcessDirect(ctx, input, sessionKey)
		if err != nil {
			fmt.Printf("Error: %v\n", err)
			continue
		}

		fmt.Printf("\n%s %s\n\n", internal.Logo, response)
	}
}

func simpleInteractiveMode(agentLoop *agent.AgentLoop, sessionKey string) {
	ctx := context.Background()
	sub := subscribeProgress(ctx, agentLoop)
	if sub != nil {
		defer sub.Close()
	}

	reader := bufio.NewReader(os.Stdin)
	for {
		fmt.Print(fmt.Sprintf("%s You: ", internal.Logo))
		line, err := reader.ReadString('\n')
		if err != nil {
			if err == io.EOF {
				fmt.Println("\nGoodbye!")
				return
			}
			fmt.Printf("Error reading input: %v\n", err)
			continue
		}

		input := strings.TrimSpace(line)
		if input == "" {
			continue
		}

		if input == "exit" || input == "quit" {
			fmt.Println("Goodbye!")
			return
		}

		response, err := agentLoop.ProcessDirect(ctx, input, sessionKey)
		if err != nil {
			fmt.Printf("Error: %v\n", err)
			continue
		}

		fmt.Printf("\n%s %s\n\n", internal.Logo, response)
	}
}
