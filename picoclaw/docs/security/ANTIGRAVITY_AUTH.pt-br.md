> Voltar ao [README](../project/README.pt-br.md)

# Guia de Autentica莽茫o e Integra莽茫o do Antigravity

## Vis茫o Geral

**Antigravity** (Google Cloud Code Assist) 茅 um provedor de modelos de IA apoiado pelo Google que oferece acesso a modelos como Claude Opus 4.6 e Gemini atrav茅s da infraestrutura de nuvem do Google. Este documento fornece um guia completo sobre como a autentica莽茫o funciona, como buscar modelos e como implementar um novo provedor no PicoClaw.

---

## 脥ndice

1. [Fluxo de Autentica莽茫o](#fluxo-de-autentica莽茫o)
2. [Detalhes da Implementa莽茫o OAuth](#detalhes-da-implementa莽茫o-oauth)
3. [Gerenciamento de Tokens](#gerenciamento-de-tokens)
4. [Busca da Lista de Modelos](#busca-da-lista-de-modelos)
5. [Rastreamento de Uso](#rastreamento-de-uso)
6. [Estrutura do Plugin do Provedor](#estrutura-do-plugin-do-provedor)
7. [Requisitos de Integra莽茫o](#requisitos-de-integra莽茫o)
8. [Endpoints da API](#endpoints-da-api)
9. [Configura莽茫o](#configura莽茫o)
10. [Criando um Novo Provedor no PicoClaw](#criando-um-novo-provedor-no-picoclaw)

---

## Fluxo de Autentica莽茫o

### 1. OAuth 2.0 com PKCE

O Antigravity utiliza **OAuth 2.0 com PKCE (Proof Key for Code Exchange)** para autentica莽茫o segura:

```
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                   鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?  Client    鈹?鈹€鈹€鈹€(1) Generate PKCE Pair鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€> 鈹?                鈹?鈹?            鈹?鈹€鈹€鈹€(2) Open Auth URL鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€> 鈹? Google OAuth   鈹?鈹?            鈹?                                   鈹?   Server       鈹?鈹?            鈹?<鈹€鈹€(3) Redirect with Code鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€ 鈹?                鈹?鈹?            鈹?                                   鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?            鈹?鈹€鈹€鈹€(4) Exchange Code for Tokens鈹€鈹€> 鈹?  Token URL     鈹?鈹?            鈹?                                   鈹?                鈹?鈹?            鈹?<鈹€鈹€(5) Access + Refresh Tokens鈹€鈹€鈹€鈹€ 鈹?                鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                   鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?```

### 2. Etapas Detalhadas

#### Etapa 1: Gerar Par芒metros PKCE
```typescript
function generatePkce(): { verifier: string; challenge: string } {
  const verifier = randomBytes(32).toString("hex");
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  return { verifier, challenge };
}
```

#### Etapa 2: Construir a URL de Autoriza莽茫o
```typescript
const AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
const REDIRECT_URI = "http://localhost:51121/oauth-callback";

function buildAuthUrl(params: { challenge: string; state: string }): string {
  const url = new URL(AUTH_URL);
  url.searchParams.set("client_id", CLIENT_ID);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("redirect_uri", REDIRECT_URI);
  url.searchParams.set("scope", SCOPES.join(" "));
  url.searchParams.set("code_challenge", params.challenge);
  url.searchParams.set("code_challenge_method", "S256");
  url.searchParams.set("state", params.state);
  url.searchParams.set("access_type", "offline");
  url.searchParams.set("prompt", "consent");
  return url.toString();
}
```

**Escopos Necess谩rios:**
```typescript
const SCOPES = [
  "https://www.googleapis.com/auth/cloud-platform",
  "https://www.googleapis.com/auth/userinfo.email",
  "https://www.googleapis.com/auth/userinfo.profile",
  "https://www.googleapis.com/auth/cclog",
  "https://www.googleapis.com/auth/experimentsandconfigs",
];
```

#### Etapa 3: Tratar o Callback OAuth

**Modo Autom谩tico (Desenvolvimento Local):**
- Iniciar um servidor HTTP local na porta 51121
- Aguardar o redirecionamento do Google
- Extrair o c贸digo de autoriza莽茫o dos par芒metros da query

**Modo Manual (Remoto/Sem Interface Gr谩fica):**
- Exibir a URL de autoriza莽茫o para o usu谩rio
- O usu谩rio completa a autentica莽茫o no navegador
- O usu谩rio cola a URL de redirecionamento completa no terminal
- Analisar o c贸digo da URL colada

#### Etapa 4: Trocar o C贸digo por Tokens
```typescript
const TOKEN_URL = "https://oauth2.googleapis.com/token";

async function exchangeCode(params: {
  code: string;
  verifier: string;
}): Promise<{ access: string; refresh: string; expires: number }> {
  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      code: params.code,
      grant_type: "authorization_code",
      redirect_uri: REDIRECT_URI,
      code_verifier: params.verifier,
    }),
  });

  const data = await response.json();
  
  return {
    access: data.access_token,
    refresh: data.refresh_token,
    expires: Date.now() + data.expires_in * 1000 - 5 * 60 * 1000, // 5 min buffer
  };
}
```

#### Etapa 5: Buscar Dados Adicionais do Usu谩rio

**E-mail do Usu谩rio:**
```typescript
async function fetchUserEmail(accessToken: string): Promise<string | undefined> {
  const response = await fetch(
    "https://www.googleapis.com/oauth2/v1/userinfo?alt=json",
    { headers: { Authorization: `Bearer ${accessToken}` } }
  );
  const data = await response.json();
  return data.email;
}
```

**ID do Projeto (Necess谩rio para chamadas de API):**
```typescript
async function fetchProjectId(accessToken: string): Promise<string> {
  const headers = {
    Authorization: `Bearer ${accessToken}`,
    "Content-Type": "application/json",
    "User-Agent": "google-api-nodejs-client/9.15.1",
    "X-Goog-Api-Client": "google-cloud-sdk vscode_cloudshelleditor/0.1",
    "Client-Metadata": JSON.stringify({
      ideType: "IDE_UNSPECIFIED",
      platform: "PLATFORM_UNSPECIFIED",
      pluginType: "GEMINI",
    }),
  };

  const response = await fetch(
    "https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist",
    {
      method: "POST",
      headers,
      body: JSON.stringify({
        metadata: {
          ideType: "IDE_UNSPECIFIED",
          platform: "PLATFORM_UNSPECIFIED",
          pluginType: "GEMINI",
        },
      }),
    }
  );

  const data = await response.json();
  return data.cloudaicompanionProject || "rising-fact-p41fc"; // Valor padr茫o de fallback
}
```

---

## Detalhes da Implementa莽茫o OAuth

### Credenciais do Cliente

**Importante:** Estas s茫o codificadas em base64 no c贸digo-fonte para sincroniza莽茫o com pi-ai:

```typescript
const decode = (s: string) => Buffer.from(s, "base64").toString();

const CLIENT_ID = decode(
  "WU9VUl9HT09HTEVfQ0xJRU5UX0lE"
);
const CLIENT_SECRET = decode("WU9VUl9HT09HTEVfQ0xJRU5UX1NFQ1JFVA==");
```

### Modos do Fluxo OAuth

1. **Fluxo Autom谩tico** (m谩quinas locais com navegador):
   - Abre o navegador automaticamente
   - O servidor de callback local captura o redirecionamento
   - Nenhuma intera莽茫o do usu谩rio necess谩ria ap贸s a autentica莽茫o inicial

2. **Fluxo Manual** (remoto/sem interface/WSL2):
   - URL exibida para copiar e colar manualmente
   - O usu谩rio completa a autentica莽茫o em um navegador externo
   - O usu谩rio cola a URL de redirecionamento completa de volta

```typescript
function shouldUseManualOAuthFlow(isRemote: boolean): boolean {
  return isRemote || isWSL2Sync();
}
```

---

## Gerenciamento de Tokens

### Estrutura do Perfil de Autentica莽茫o

```typescript
type OAuthCredential = {
  type: "oauth";
  provider: "google-antigravity";
  access: string;           // Token de acesso
  refresh: string;          // Token de atualiza莽茫o
  expires: number;          // Timestamp de expira莽茫o (ms desde epoch)
  email?: string;           // E-mail do usu谩rio
  projectId?: string;       // ID do projeto Google Cloud
};
```

### Atualiza莽茫o de Tokens

A credencial inclui um token de atualiza莽茫o que pode ser usado para obter novos tokens de acesso quando o atual expira. A expira莽茫o 茅 definida com um buffer de 5 minutos para evitar condi莽玫es de corrida.

---

## Busca da Lista de Modelos

### Buscar Modelos Dispon铆veis

```typescript
const BASE_URL = "https://cloudcode-pa.googleapis.com";

async function fetchAvailableModels(
  accessToken: string,
  projectId: string
): Promise<Model[]> {
  const headers = {
    Authorization: `Bearer ${accessToken}`,
    "Content-Type": "application/json",
    "User-Agent": "antigravity",
    "X-Goog-Api-Client": "google-cloud-sdk vscode_cloudshelleditor/0.1",
  };

  const response = await fetch(
    `${BASE_URL}/v1internal:fetchAvailableModels`,
    {
      method: "POST",
      headers,
      body: JSON.stringify({ project: projectId }),
    }
  );

  const data = await response.json();
  
  // Retorna modelos com informa莽玫es de cota
  return Object.entries(data.models).map(([modelId, modelInfo]) => ({
    id: modelId,
    displayName: modelInfo.displayName,
    quotaInfo: {
      remainingFraction: modelInfo.quotaInfo?.remainingFraction,
      resetTime: modelInfo.quotaInfo?.resetTime,
      isExhausted: modelInfo.quotaInfo?.isExhausted,
    },
  }));
}
```

### Formato da Resposta

```typescript
type FetchAvailableModelsResponse = {
  models?: Record<string, {
    displayName?: string;
    quotaInfo?: {
      remainingFraction?: number | string;
      resetTime?: string;      // Timestamp ISO 8601
      isExhausted?: boolean;
    };
  }>;
};
```

---

## Rastreamento de Uso

### Buscar Dados de Uso

```typescript
export async function fetchAntigravityUsage(
  token: string,
  timeoutMs: number
): Promise<ProviderUsageSnapshot> {
  // 1. Buscar cr茅ditos e informa莽玫es do plano
  const loadCodeAssistRes = await fetch(
    `${BASE_URL}/v1internal:loadCodeAssist`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        metadata: {
          ideType: "ANTIGRAVITY",
          platform: "PLATFORM_UNSPECIFIED",
          pluginType: "GEMINI",
        },
      }),
    }
  );

  // Extrair informa莽玫es de cr茅ditos
  const { availablePromptCredits, planInfo, currentTier } = data;
  
  // 2. Buscar cotas dos modelos
  const modelsRes = await fetch(
    `${BASE_URL}/v1internal:fetchAvailableModels`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: JSON.stringify({ project: projectId }),
    }
  );

  // Construir janelas de uso
  return {
    provider: "google-antigravity",
    displayName: "Google Antigravity",
    windows: [
      { label: "Credits", usedPercent: calculateUsedPercent(available, monthly) },
      // Cotas individuais dos modelos...
    ],
    plan: currentTier?.name || planType,
  };
}
```

### Estrutura da Resposta de Uso

```typescript
type ProviderUsageSnapshot = {
  provider: "google-antigravity";
  displayName: string;
  windows: UsageWindow[];
  plan?: string;
  error?: string;
};

type UsageWindow = {
  label: string;           // "Credits" ou ID do modelo
  usedPercent: number;     // 0-100
  resetAt?: number;        // Timestamp de quando a cota 茅 redefinida
};
```

---

## Estrutura do Plugin do Provedor

### Defini莽茫o do Plugin

```typescript
const antigravityPlugin = {
  id: "google-antigravity-auth",
  name: "Google Antigravity Auth",
  description: "OAuth flow for Google Antigravity (Cloud Code Assist)",
  configSchema: emptyPluginConfigSchema(),
  
  register(api: PicoClawPluginApi) {
    api.registerProvider({
      id: "google-antigravity",
      label: "Google Antigravity",
      docsPath: "/providers/models",
      aliases: ["antigravity"],
      
      auth: [
        {
          id: "oauth",
          label: "Google OAuth",
          hint: "PKCE + localhost callback",
          kind: "oauth",
          run: async (ctx: ProviderAuthContext) => {
            // Implementa莽茫o OAuth aqui
          },
        },
      ],
    });
  },
};
```

### ProviderAuthContext

```typescript
type ProviderAuthContext = {
  config: PicoClawConfig;
  agentDir?: string;
  workspaceDir?: string;
  prompter: WizardPrompter;      // Prompts/notifica莽玫es da UI
  runtime: RuntimeEnv;           // Logging, etc.
  isRemote: boolean;             // Se est谩 executando remotamente
  openUrl: (url: string) => Promise<void>;  // Abridor de navegador
  oauth: {
    createVpsAwareHandlers: Function;
  };
};
```

### ProviderAuthResult

```typescript
type ProviderAuthResult = {
  profiles: Array<{
    profileId: string;
    credential: AuthProfileCredential;
  }>;
  configPatch?: Partial<PicoClawConfig>;
  defaultModel?: string;
  notes?: string[];
};
```

---

## Requisitos de Integra莽茫o

### 1. Ambiente/Depend锚ncias Necess谩rios

- Go 鈮?1.25
- Base de c贸digo do PicoClaw (`pkg/providers/` e `pkg/auth/`)
- Pacotes da biblioteca padr茫o `crypto` e `net/http`

### 2. Cabe莽alhos Necess谩rios para Chamadas de API

```typescript
const REQUIRED_HEADERS = {
  "Authorization": `Bearer ${accessToken}`,
  "Content-Type": "application/json",
  "User-Agent": "antigravity",  // ou "google-api-nodejs-client/9.15.1"
  "X-Goog-Api-Client": "google-cloud-sdk vscode_cloudshelleditor/0.1",
};

// Para chamadas loadCodeAssist, incluir tamb茅m:
const CLIENT_METADATA = {
  ideType: "ANTIGRAVITY",  // ou "IDE_UNSPECIFIED"
  platform: "PLATFORM_UNSPECIFIED",
  pluginType: "GEMINI",
};
```

### 3. Sanitiza莽茫o de Schemas de Modelos

O Antigravity usa modelos compat铆veis com Gemini, ent茫o os schemas de ferramentas devem ser sanitizados:

```typescript
const GOOGLE_SCHEMA_UNSUPPORTED_KEYWORDS = new Set([
  "patternProperties",
  "additionalProperties",
  "$schema",
  "$id",
  "$ref",
  "$defs",
  "definitions",
  "examples",
  "minLength",
  "maxLength",
  "minimum",
  "maximum",
  "multipleOf",
  "pattern",
  "format",
  "minItems",
  "maxItems",
  "uniqueItems",
  "minProperties",
  "maxProperties",
]);

// Limpar schema antes de enviar
function cleanToolSchemaForGemini(schema: Record<string, unknown>): unknown {
  // Remover palavras-chave n茫o suportadas
  // Garantir que o n铆vel superior tenha type: "object"
  // Achatar uni玫es anyOf/oneOf
}
```

### 4. Tratamento de Blocos de Pensamento (Modelos Claude)

Para modelos Claude via Antigravity, os blocos de pensamento requerem tratamento especial:

```typescript
const ANTIGRAVITY_SIGNATURE_RE = /^[A-Za-z0-9+/]+={0,2}$/;

export function sanitizeAntigravityThinkingBlocks(
  messages: AgentMessage[]
): AgentMessage[] {
  // Validar assinaturas de pensamento
  // Normalizar campos de assinatura
  // Descartar blocos de pensamento n茫o assinados
}
```

---

## Endpoints da API

### Endpoints de Autentica莽茫o

| Endpoint | M茅todo | Finalidade |
|----------|--------|-----------|
| `https://accounts.google.com/o/oauth2/v2/auth` | GET | Autoriza莽茫o OAuth |
| `https://oauth2.googleapis.com/token` | POST | Troca de tokens |
| `https://www.googleapis.com/oauth2/v1/userinfo` | GET | Informa莽玫es do usu谩rio (e-mail) |

### Endpoints do Cloud Code Assist

| Endpoint | M茅todo | Finalidade |
|----------|--------|-----------|
| `https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist` | POST | Carregar informa莽玫es do projeto, cr茅ditos, plano |
| `https://cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels` | POST | Listar modelos dispon铆veis com cotas |
| `https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse` | POST | Endpoint de streaming de chat |

**Formato de Requisi莽茫o da API (Chat):**
O endpoint `v1internal:streamGenerateContent` espera um envelope encapsulando a requisi莽茫o Gemini padr茫o:

```json
{
  "project": "your-project-id",
  "model": "model-id",
  "request": {
    "contents": [...],
    "systemInstruction": {...},
    "generationConfig": {...},
    "tools": [...]
  },
  "requestType": "agent",
  "userAgent": "antigravity",
  "requestId": "agent-timestamp-random"
}
```

**Formato de Resposta da API (SSE):**
Cada mensagem SSE (`data: {...}`) 茅 encapsulada em um campo `response`:

```json
{
  "response": {
    "candidates": [...],
    "usageMetadata": {...},
    "modelVersion": "...",
    "responseId": "..."
  },
  "traceId": "...",
  "metadata": {}
}
```

---

## Configura莽茫o

### Configura莽茫o do config.json

```json
{
  "model_list": [
    {
      "model_name": "gemini-flash",
      "model": "antigravity/gemini-3-flash",
      "auth_method": "oauth"
    }
  ],
  "agents": {
    "defaults": {
      "model_name": "gemini-flash"
    }
  }
}
```

### Armazenamento do Perfil de Autentica莽茫o

Os perfis de autentica莽茫o s茫o armazenados em `~/.picoclaw/auth.json`:

```json
{
  "credentials": {
    "google-antigravity": {
      "access_token": "ya29...",
      "refresh_token": "1//...",
      "expires_at": "2026-01-01T00:00:00Z",
      "provider": "google-antigravity",
      "auth_method": "oauth",
      "email": "user@example.com",
      "project_id": "my-project-id"
    }
  }
}
```

---

## Criando um Novo Provedor no PicoClaw

Os provedores do PicoClaw s茫o implementados como pacotes Go em `pkg/providers/`. Para adicionar um novo provedor:

### Implementa莽茫o Passo a Passo

#### 1. Criar o Arquivo do Provedor

Crie um novo arquivo Go em `pkg/providers/`:

```
pkg/providers/
鈹斺攢鈹€ your_provider.go
```

#### 2. Implementar a Interface Provider

Seu provedor deve implementar a interface `Provider` definida em `pkg/providers/types.go`:

```go
package providers

type YourProvider struct {
    apiKey  string
    apiBase string
}

func NewYourProvider(apiKey, apiBase, proxy string) *YourProvider {
    if apiBase == "" {
        apiBase = "https://api.your-provider.com/v1"
    }
    return &YourProvider{apiKey: apiKey, apiBase: apiBase}
}

func (p *YourProvider) Chat(ctx context.Context, messages []Message, tools []Tool, cb StreamCallback) error {
    // Implementar conclus茫o de chat com streaming
}
```

#### 3. Registrar na Factory

Adicione seu provedor ao switch de protocolo em `pkg/providers/factory.go`:

```go
case "your-provider":
    return NewYourProvider(sel.apiKey, sel.apiBase, sel.proxy), nil
```

#### 4. Adicionar Configura莽茫o Padr茫o (Opcional)

Adicione uma entrada padr茫o em `pkg/config/defaults.go`:

```go
{
    ModelName: "your-model",
    Model:     "your-provider/model-name",
    APIKey:    "",
},
```

#### 5. Adicionar Suporte de Autentica莽茫o (Opcional)

Se seu provedor requer OAuth ou autentica莽茫o especial, adicione um caso em `cmd/picoclaw/internal/auth/helpers.go`:

```go
case "your-provider":
    authLoginYourProvider()
```

#### 6. Configurar via `config.json`

```json
{
  "model_list": [
    {
      "model_name": "your-model",
      "model": "your-provider/model-name",
      "api_keys": ["your-api-key"],
      "api_base": "https://api.your-provider.com/v1"
    }
  ]
}
```

---

## Testando Sua Implementa莽茫o

### Comandos CLI

```bash
# Autenticar com um provedor
picoclaw auth login --provider your-provider

# Listar modelos (para Antigravity)
picoclaw auth models

# Iniciar o gateway
picoclaw gateway

# Executar um agente com um modelo espec铆fico
picoclaw agent -m "Hello" --model your-model
```

### Vari谩veis de Ambiente para Testes

```bash
# Substituir o modelo padr茫o
export PICOCLAW_AGENTS_DEFAULTS_MODEL=your-model

# Substituir configura莽玫es do provedor
export PICOCLAW_MODEL_LIST='[{"model_name":"your-model","model":"your-provider/model-name","api_keys":["..."]}]'
```

---

## Refer锚ncias

- **Arquivos Fonte:**
  - `pkg/providers/antigravity_provider.go` - Implementa莽茫o do provedor Antigravity
  - `pkg/auth/oauth.go` - Implementa莽茫o do fluxo OAuth
  - `pkg/auth/store.go` - Armazenamento de credenciais de autentica莽茫o (`~/.picoclaw/auth.json`)
  - `pkg/providers/factory.go` - Factory de provedores e roteamento de protocolo
  - `pkg/providers/types.go` - Defini莽玫es da interface do provedor
  - `cmd/picoclaw/internal/auth/helpers.go` - Comandos CLI de autentica莽茫o

- **Documenta莽茫o:**
  - `docs/ANTIGRAVITY_USAGE.md` - Guia de uso do Antigravity
  - `docs/migration/model-list-migration.md` - Guia de migra莽茫o

---

## Observa莽玫es

1. **Projeto Google Cloud:** O Antigravity requer que o Gemini for Google Cloud esteja habilitado no seu projeto Google Cloud
2. **Cotas:** Usa cotas do projeto Google Cloud (sem cobran莽a separada)
3. **Acesso a Modelos:** Os modelos dispon铆veis dependem da configura莽茫o do seu projeto Google Cloud
4. **Blocos de Pensamento:** Modelos Claude via Antigravity requerem tratamento especial de blocos de pensamento com assinaturas
5. **Sanitiza莽茫o de Schemas:** Os schemas de ferramentas devem ser sanitizados para remover palavras-chave JSON Schema n茫o suportadas

---

---

## Tratamento de Erros Comuns

### 1. Limita莽茫o de Taxa (HTTP 429)

O Antigravity retorna um erro 429 quando as cotas do projeto/modelo est茫o esgotadas. A resposta de erro frequentemente cont茅m um `quotaResetDelay` no campo `details`.

**Exemplo de Erro 429:**
```json
{
  "error": {
    "code": 429,
    "message": "You have exhausted your capacity on this model. Your quota will reset after 4h30m28s.",
    "status": "RESOURCE_EXHAUSTED",
    "details": [
      {
        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
        "metadata": {
          "quotaResetDelay": "4h30m28.060903746s"
        }
      }
    ]
  }
}
```

### 2. Respostas Vazias (Modelos Restritos)

Alguns modelos podem aparecer na lista de modelos dispon铆veis, mas retornar uma resposta vazia (200 OK mas stream SSE vazio). Isso geralmente acontece com modelos em preview ou restritos que o projeto atual n茫o tem permiss茫o para usar.

**Tratamento:** Tratar respostas vazias como erros informando ao usu谩rio que o modelo pode estar restrito ou inv谩lido para seu projeto.

---

## Solu莽茫o de Problemas

### "Token expired" (token expirado)
- Atualizar tokens OAuth: `picoclaw auth login --provider antigravity`

### "Gemini for Google Cloud is not enabled" (Gemini for Google Cloud n茫o est谩 habilitado)
- Habilitar a API no seu Google Cloud Console

### "Project not found" (projeto n茫o encontrado)
- Verificar se seu projeto Google Cloud tem as APIs necess谩rias habilitadas
- Verificar se o ID do projeto foi obtido corretamente durante a autentica莽茫o

### Modelos n茫o aparecem na lista
- Verificar se a autentica莽茫o OAuth foi conclu铆da com sucesso
- Verificar o armazenamento do perfil de autentica莽茫o: `~/.picoclaw/auth.json`
- Executar novamente `picoclaw auth login --provider antigravity`
