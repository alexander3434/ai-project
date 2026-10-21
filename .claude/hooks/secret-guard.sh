#!/bin/sh
# Secret guard: blocks Write/Edit/Bash tool calls whose payload contains a secret.
payload=$(cat)
ENV_FILE="/Users/murkka/Work/ai-project/.env"
hit=""
if printf '%s' "$payload" | grep -qE 'sk-[A-Za-z0-9]{16,}'; then hit=1; fi
for var in DEEPSEEK_API_KEY STAGE_DB_PASSWORD; do
  val=$(grep -E "^${var}=." "$ENV_FILE" 2>/dev/null | head -1 | cut -d= -f2- | sed 's/^"//; s/"$//' | tr -d '[:space:]')
  if [ -n "$val" ] && printf '%s' "$payload" | grep -qF -- "$val"; then hit=1; fi
done
if [ -n "$hit" ]; then
  echo '{"systemMessage":"Secret guard: в содержимом инструмента обнаружен секрет — операция заблокирована.","hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"Secret value detected in tool input"}}'
fi
exit 0
