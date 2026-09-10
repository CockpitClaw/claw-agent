> Quay l岷 [README](../project/README.vi.md)

# H瓢峄沶g d岷玭 X谩c th峄眂 v脿 T铆ch h峄 Antigravity

## T峄昻g quan

**Antigravity** (Google Cloud Code Assist) l脿 nh脿 cung c岷 m么 h矛nh AI 膽瓢峄 Google h峄?tr峄? cung c岷 quy峄乶 truy c岷璸 v脿o c谩c m么 h矛nh nh瓢 Claude Opus 4.6 v脿 Gemini th么ng qua h岷?t岷g 膽谩m m芒y c峄 Google. T脿i li峄噓 n脿y cung c岷 h瓢峄沶g d岷玭 膽岷 膽峄?v峄?c谩ch x谩c th峄眂 ho岷 膽峄檔g, c谩ch l岷 danh s谩ch m么 h矛nh v脿 c谩ch tri峄僴 khai nh脿 cung c岷 m峄沬 trong PicoClaw.

---

## M峄 l峄

1. [Lu峄搉g x谩c th峄眂](#lu峄搉g-x谩c-th峄眂)
2. [Chi ti岷縯 tri峄僴 khai OAuth](#chi-ti岷縯-tri峄僴-khai-oauth)
3. [Qu岷 l媒 token](#qu岷-l媒-token)
4. [L岷 danh s谩ch m么 h矛nh](#l岷-danh-s谩ch-m么-h矛nh)
5. [Theo d玫i m峄ヽ s峄?d峄g](#theo-d玫i-m峄ヽ-s峄?d峄g)
6. [C岷 tr煤c plugin nh脿 cung c岷](#c岷-tr煤c-plugin-nh脿-cung-c岷)
7. [Y锚u c岷 t铆ch h峄](#y锚u-c岷-t铆ch-h峄)
8. [C谩c endpoint API](#c谩c-endpoint-api)
9. [C岷 h矛nh](#c岷-h矛nh)
10. [T岷 nh脿 cung c岷 m峄沬 trong PicoClaw](#t岷-nh脿-cung-c岷-m峄沬-trong-picoclaw)

---

## Lu峄搉g x谩c th峄眂

### 1. OAuth 2.0 v峄沬 PKCE

Antigravity s峄?d峄g **OAuth 2.0 v峄沬 PKCE (Proof Key for Code Exchange)** 膽峄?x谩c th峄眂 an to脿n:

```
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                   鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?  Client    鈹?鈹€鈹€鈹€(1) Generate PKCE Pair鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€> 鈹?                鈹?鈹?            鈹?鈹€鈹€鈹€(2) Open Auth URL鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€> 鈹? Google OAuth   鈹?鈹?            鈹?                                   鈹?   Server       鈹?鈹?            鈹?<鈹€鈹€(3) Redirect with Code鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€ 鈹?                鈹?鈹?            鈹?                                   鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?            鈹?鈹€鈹€鈹€(4) Exchange Code for Tokens鈹€鈹€> 鈹?  Token URL     鈹?鈹?            鈹?                                   鈹?                鈹?鈹?            鈹?<鈹€鈹€(5) Access + Refresh Tokens鈹€鈹€鈹€鈹€ 鈹?                鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                   鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?```

### 2. C谩c b瓢峄沜 chi ti岷縯

#### B瓢峄沜 1: T岷 tham s峄?PKCE
```typescript
function generatePkce(): { verifier: string; challenge: string } {
  const verifier = randomBytes(32).toString("hex");
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  return { verifier, challenge };
}
```

#### B瓢峄沜 2: X芒y d峄眓g URL 峄 quy峄乶
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

**C谩c ph岷 vi quy峄乶 c岷 thi岷縯:**
```typescript
const SCOPES = [
  "https://www.googleapis.com/auth/cloud-platform",
  "https://www.googleapis.com/auth/userinfo.email",
  "https://www.googleapis.com/auth/userinfo.profile",
  "https://www.googleapis.com/auth/cclog",
  "https://www.googleapis.com/auth/experimentsandconfigs",
];
```

#### B瓢峄沜 3: X峄?l媒 callback OAuth

**Ch岷?膽峄?t峄?膽峄檔g (Ph谩t tri峄僴 c峄 b峄?:**
- Kh峄焛 膽峄檔g m谩y ch峄?HTTP c峄 b峄?tr锚n c峄昻g 51121
- Ch峄?chuy峄僴 h瓢峄沶g t峄?Google
- Tr铆ch xu岷 m茫 峄 quy峄乶 t峄?tham s峄?truy v岷

**Ch岷?膽峄?th峄?c么ng (T峄?xa/Kh么ng c贸 giao di峄噉):**
- Hi峄僴 th峄?URL 峄 quy峄乶 cho ng瓢峄漣 d霉ng
- Ng瓢峄漣 d霉ng ho脿n t岷 x谩c th峄眂 trong tr矛nh duy峄噒
- Ng瓢峄漣 d霉ng d谩n URL chuy峄僴 h瓢峄沶g 膽岷 膽峄?v脿o terminal
- Ph芒n t铆ch m茫 t峄?URL 膽茫 d谩n

#### B瓢峄沜 4: 膼峄昳 m茫 l岷 token
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

#### B瓢峄沜 5: L岷 d峄?li峄噓 ng瓢峄漣 d霉ng b峄?sung

**Email ng瓢峄漣 d霉ng:**
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

**ID d峄?谩n (B岷痶 bu峄檆 cho c谩c l峄噉h g峄峣 API):**
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
  return data.cloudaicompanionProject || "rising-fact-p41fc"; // Gi谩 tr峄?m岷穋 膽峄媙h d峄?ph貌ng
}
```

---

## Chi ti岷縯 tri峄僴 khai OAuth

### Th么ng tin x谩c th峄眂 client

**Quan tr峄峮g:** C谩c gi谩 tr峄?n脿y 膽瓢峄 m茫 h贸a base64 trong m茫 ngu峄搉 膽峄?膽峄搉g b峄?v峄沬 pi-ai:

```typescript
const decode = (s: string) => Buffer.from(s, "base64").toString();

const CLIENT_ID = decode(
  "WU9VUl9HT09HTEVfQ0xJRU5UX0lE"
);
const CLIENT_SECRET = decode("WU9VUl9HT09HTEVfQ0xJRU5UX1NFQ1JFVA==");
```

### C谩c ch岷?膽峄?lu峄搉g OAuth

1. **Lu峄搉g t峄?膽峄檔g** (M谩y c峄 b峄?c贸 tr矛nh duy峄噒):
   - T峄?膽峄檔g m峄?tr矛nh duy峄噒
   - M谩y ch峄?callback c峄 b峄?b岷痶 chuy峄僴 h瓢峄沶g
   - Kh么ng c岷 t瓢啤ng t谩c ng瓢峄漣 d霉ng sau x谩c th峄眂 ban 膽岷

2. **Lu峄搉g th峄?c么ng** (T峄?xa/Kh么ng c贸 giao di峄噉/WSL2):
   - Hi峄僴 th峄?URL 膽峄?sao ch茅p-d谩n th峄?c么ng
   - Ng瓢峄漣 d霉ng ho脿n t岷 x谩c th峄眂 trong tr矛nh duy峄噒 b锚n ngo脿i
   - Ng瓢峄漣 d霉ng d谩n l岷 URL chuy峄僴 h瓢峄沶g 膽岷 膽峄?
```typescript
function shouldUseManualOAuthFlow(isRemote: boolean): boolean {
  return isRemote || isWSL2Sync();
}
```

---

## Qu岷 l媒 token

### C岷 tr煤c h峄?s啤 x谩c th峄眂

```typescript
type OAuthCredential = {
  type: "oauth";
  provider: "google-antigravity";
  access: string;           // Token truy c岷璸
  refresh: string;          // Token l脿m m峄沬
  expires: number;          // D岷 th峄漣 gian h岷縯 h岷 (ms k峄?t峄?epoch)
  email?: string;           // Email ng瓢峄漣 d霉ng
  projectId?: string;       // ID d峄?谩n Google Cloud
};
```

### L脿m m峄沬 token

Th么ng tin x谩c th峄眂 bao g峄搈 token l脿m m峄沬 c贸 th峄?膽瓢峄 s峄?d峄g 膽峄?l岷 token truy c岷璸 m峄沬 khi token hi峄噉 t岷 h岷縯 h岷. Th峄漣 gian h岷縯 h岷 膽瓢峄 膽岷穞 v峄沬 b峄?膽峄噈 5 ph煤t 膽峄?tr谩nh 膽i峄乽 ki峄噉 tranh ch岷.

---

## L岷 danh s谩ch m么 h矛nh

### L岷 c谩c m么 h矛nh kh岷?d峄g

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
  
  // Tr岷?v峄?c谩c m么 h矛nh k猫m th么ng tin h岷 m峄ヽ
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

### 膼峄媙h d岷g ph岷 h峄搃

```typescript
type FetchAvailableModelsResponse = {
  models?: Record<string, {
    displayName?: string;
    quotaInfo?: {
      remainingFraction?: number | string;
      resetTime?: string;      // D岷 th峄漣 gian ISO 8601
      isExhausted?: boolean;
    };
  }>;
};
```

---

## Theo d玫i m峄ヽ s峄?d峄g

### L岷 d峄?li峄噓 s峄?d峄g

```typescript
export async function fetchAntigravityUsage(
  token: string,
  timeoutMs: number
): Promise<ProviderUsageSnapshot> {
  // 1. L岷 th么ng tin t铆n d峄g v脿 g贸i d峄媍h v峄?  const loadCodeAssistRes = await fetch(
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

  // Tr铆ch xu岷 th么ng tin t铆n d峄g
  const { availablePromptCredits, planInfo, currentTier } = data;
  
  // 2. L岷 h岷 m峄ヽ m么 h矛nh
  const modelsRes = await fetch(
    `${BASE_URL}/v1internal:fetchAvailableModels`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: JSON.stringify({ project: projectId }),
    }
  );

  // X芒y d峄眓g c峄璦 s峄?s峄?d峄g
  return {
    provider: "google-antigravity",
    displayName: "Google Antigravity",
    windows: [
      { label: "Credits", usedPercent: calculateUsedPercent(available, monthly) },
      // H岷 m峄ヽ t峄玭g m么 h矛nh...
    ],
    plan: currentTier?.name || planType,
  };
}
```

### C岷 tr煤c ph岷 h峄搃 s峄?d峄g

```typescript
type ProviderUsageSnapshot = {
  provider: "google-antigravity";
  displayName: string;
  windows: UsageWindow[];
  plan?: string;
  error?: string;
};

type UsageWindow = {
  label: string;           // "Credits" ho岷穋 ID m么 h矛nh
  usedPercent: number;     // 0-100
  resetAt?: number;        // D岷 th峄漣 gian khi h岷 m峄ヽ 膽瓢峄 膽岷穞 l岷
};
```

---

## C岷 tr煤c plugin nh脿 cung c岷

### 膼峄媙h ngh末a plugin

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
            // Tri峄僴 khai OAuth t岷 膽芒y
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
  prompter: WizardPrompter;      // L峄漣 nh岷痗/th么ng b谩o UI
  runtime: RuntimeEnv;           // Ghi log, v.v.
  isRemote: boolean;             // C贸 膽ang ch岷 t峄?xa kh么ng
  openUrl: (url: string) => Promise<void>;  // M峄?tr矛nh duy峄噒
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

## Y锚u c岷 t铆ch h峄

### 1. M么i tr瓢峄漬g/Ph峄?thu峄檆 c岷 thi岷縯

- Go 鈮?1.25
- M茫 ngu峄搉 PicoClaw (`pkg/providers/` v脿 `pkg/auth/`)
- C谩c g贸i th瓢 vi峄噉 chu岷﹏ `crypto` v脿 `net/http`

### 2. C谩c header b岷痶 bu峄檆 cho l峄噉h g峄峣 API

```typescript
const REQUIRED_HEADERS = {
  "Authorization": `Bearer ${accessToken}`,
  "Content-Type": "application/json",
  "User-Agent": "antigravity",  // ho岷穋 "google-api-nodejs-client/9.15.1"
  "X-Goog-Api-Client": "google-cloud-sdk vscode_cloudshelleditor/0.1",
};

// 膼峄慽 v峄沬 c谩c l峄噉h g峄峣 loadCodeAssist, c农ng bao g峄搈:
const CLIENT_METADATA = {
  ideType: "ANTIGRAVITY",  // ho岷穋 "IDE_UNSPECIFIED"
  platform: "PLATFORM_UNSPECIFIED",
  pluginType: "GEMINI",
};
```

### 3. L脿m s岷h schema m么 h矛nh

Antigravity s峄?d峄g c谩c m么 h矛nh t瓢啤ng th铆ch Gemini, v矛 v岷瓂 schema c么ng c峄?ph岷 膽瓢峄 l脿m s岷h:

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

// L脿m s岷h schema tr瓢峄沜 khi g峄璱
function cleanToolSchemaForGemini(schema: Record<string, unknown>): unknown {
  // X贸a c谩c t峄?kh贸a kh么ng 膽瓢峄 h峄?tr峄?  // 膼岷 b岷 c岷 cao nh岷 c贸 type: "object"
  // L脿m ph岷硁g c谩c union anyOf/oneOf
}
```

### 4. X峄?l媒 kh峄慽 suy ngh末 (M么 h矛nh Claude)

膼峄慽 v峄沬 c谩c m么 h矛nh Claude qua Antigravity, kh峄慽 suy ngh末 c岷 x峄?l媒 膽岷穋 bi峄噒:

```typescript
const ANTIGRAVITY_SIGNATURE_RE = /^[A-Za-z0-9+/]+={0,2}$/;

export function sanitizeAntigravityThinkingBlocks(
  messages: AgentMessage[]
): AgentMessage[] {
  // X谩c th峄眂 ch峄?k媒 suy ngh末
  // Chu岷﹏ h贸a c谩c tr瓢峄漬g ch峄?k媒
  // Lo岷 b峄?c谩c kh峄慽 suy ngh末 ch瓢a k媒
}
```

---

## C谩c endpoint API

### Endpoint x谩c th峄眂

| Endpoint | Ph瓢啤ng th峄ヽ | M峄 膽铆ch |
|----------|------------|----------|
| `https://accounts.google.com/o/oauth2/v2/auth` | GET | 峄 quy峄乶 OAuth |
| `https://oauth2.googleapis.com/token` | POST | Trao 膽峄昳 token |
| `https://www.googleapis.com/oauth2/v1/userinfo` | GET | Th么ng tin ng瓢峄漣 d霉ng (email) |

### Endpoint Cloud Code Assist

| Endpoint | Ph瓢啤ng th峄ヽ | M峄 膽铆ch |
|----------|------------|----------|
| `https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist` | POST | T岷 th么ng tin d峄?谩n, t铆n d峄g, g贸i d峄媍h v峄?|
| `https://cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels` | POST | Li峄噒 k锚 c谩c m么 h矛nh kh岷?d峄g k猫m h岷 m峄ヽ |
| `https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse` | POST | Endpoint streaming chat |

**膼峄媙h d岷g y锚u c岷 API (Chat):**
Endpoint `v1internal:streamGenerateContent` y锚u c岷 m峄檛 envelope bao b峄峜 y锚u c岷 Gemini ti锚u chu岷﹏:

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

**膼峄媙h d岷g ph岷 h峄搃 API (SSE):**
M峄梚 th么ng 膽i峄噋 SSE (`data: {...}`) 膽瓢峄 bao b峄峜 trong tr瓢峄漬g `response`:

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

## C岷 h矛nh

### C岷 h矛nh config.json

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

### L瓢u tr峄?h峄?s啤 x谩c th峄眂

H峄?s啤 x谩c th峄眂 膽瓢峄 l瓢u tr峄?trong `~/.picoclaw/auth.json`:

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

## T岷 nh脿 cung c岷 m峄沬 trong PicoClaw

C谩c nh脿 cung c岷 PicoClaw 膽瓢峄 tri峄僴 khai d瓢峄沬 d岷g g贸i Go trong `pkg/providers/`. 膼峄?th锚m nh脿 cung c岷 m峄沬:

### Tri峄僴 khai t峄玭g b瓢峄沜

#### 1. T岷 file nh脿 cung c岷

T岷 file Go m峄沬 trong `pkg/providers/`:

```
pkg/providers/
鈹斺攢鈹€ your_provider.go
```

#### 2. Tri峄僴 khai interface Provider

Nh脿 cung c岷 c峄 b岷 ph岷 tri峄僴 khai interface `Provider` 膽瓢峄 膽峄媙h ngh末a trong `pkg/providers/types.go`:

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
    // Tri峄僴 khai ho脿n th脿nh chat v峄沬 streaming
}
```

#### 3. 膼膬ng k媒 trong factory

Th锚m nh脿 cung c岷 c峄 b岷 v脿o switch giao th峄ヽ trong `pkg/providers/factory.go`:

```go
case "your-provider":
    return NewYourProvider(sel.apiKey, sel.apiBase, sel.proxy), nil
```

#### 4. Th锚m c岷 h矛nh m岷穋 膽峄媙h (T霉y ch峄峮)

Th锚m m峄 m岷穋 膽峄媙h trong `pkg/config/defaults.go`:

```go
{
    ModelName: "your-model",
    Model:     "your-provider/model-name",
    APIKey:    "",
},
```

#### 5. Th锚m h峄?tr峄?x谩c th峄眂 (T霉y ch峄峮)

N岷縰 nh脿 cung c岷 c峄 b岷 y锚u c岷 OAuth ho岷穋 x谩c th峄眂 膽岷穋 bi峄噒, th锚m case v脿o `cmd/picoclaw/internal/auth/helpers.go`:

```go
case "your-provider":
    authLoginYourProvider()
```

#### 6. C岷 h矛nh qua `config.json`

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

## Ki峄僲 th峄?tri峄僴 khai c峄 b岷

### L峄噉h CLI

```bash
# X谩c th峄眂 v峄沬 nh脿 cung c岷
picoclaw auth login --provider your-provider

# Li峄噒 k锚 m么 h矛nh (cho Antigravity)
picoclaw auth models

# Kh峄焛 膽峄檔g gateway
picoclaw gateway

# Ch岷 agent v峄沬 m么 h矛nh c峄?th峄?picoclaw agent -m "Hello" --model your-model
```

### Bi岷縩 m么i tr瓢峄漬g cho ki峄僲 th峄?
```bash
# Ghi 膽猫 m么 h矛nh m岷穋 膽峄媙h
export PICOCLAW_AGENTS_DEFAULTS_MODEL=your-model

# Ghi 膽猫 c脿i 膽岷穞 nh脿 cung c岷
export PICOCLAW_MODEL_LIST='[{"model_name":"your-model","model":"your-provider/model-name","api_keys":["..."]}]'
```

---

## T脿i li峄噓 tham kh岷

- **File ngu峄搉:**
  - `pkg/providers/antigravity_provider.go` - Tri峄僴 khai nh脿 cung c岷 Antigravity
  - `pkg/auth/oauth.go` - Tri峄僴 khai lu峄搉g OAuth
  - `pkg/auth/store.go` - L瓢u tr峄?th么ng tin x谩c th峄眂 (`~/.picoclaw/auth.json`)
  - `pkg/providers/factory.go` - Factory nh脿 cung c岷 v脿 膽峄媙h tuy岷縩 giao th峄ヽ
  - `pkg/providers/types.go` - 膼峄媙h ngh末a interface nh脿 cung c岷
  - `cmd/picoclaw/internal/auth/helpers.go` - L峄噉h CLI x谩c th峄眂

- **T脿i li峄噓:**
  - `docs/ANTIGRAVITY_USAGE.md` - H瓢峄沶g d岷玭 s峄?d峄g Antigravity
  - `docs/migration/model-list-migration.md` - H瓢峄沶g d岷玭 di chuy峄僴

---

## L瓢u 媒

1. **D峄?谩n Google Cloud:** Antigravity y锚u c岷 Gemini for Google Cloud 膽瓢峄 b岷璽 tr锚n d峄?谩n Google Cloud c峄 b岷
2. **H岷 m峄ヽ:** S峄?d峄g h岷 m峄ヽ d峄?谩n Google Cloud (kh么ng t铆nh ph铆 ri锚ng)
3. **Truy c岷璸 m么 h矛nh:** C谩c m么 h矛nh kh岷?d峄g ph峄?thu峄檆 v脿o c岷 h矛nh d峄?谩n Google Cloud c峄 b岷
4. **Kh峄慽 suy ngh末:** M么 h矛nh Claude qua Antigravity y锚u c岷 x峄?l媒 膽岷穋 bi峄噒 kh峄慽 suy ngh末 c贸 ch峄?k媒
5. **L脿m s岷h schema:** Schema c么ng c峄?ph岷 膽瓢峄 l脿m s岷h 膽峄?lo岷 b峄?c谩c t峄?kh贸a JSON Schema kh么ng 膽瓢峄 h峄?tr峄?
---

## X峄?l媒 l峄梚 th瓢峄漬g g岷穚

### 1. Gi峄沬 h岷 t峄慶 膽峄?(HTTP 429)

Antigravity tr岷?v峄?l峄梚 429 khi h岷 m峄ヽ d峄?谩n/m么 h矛nh 膽茫 c岷 ki峄噒. Ph岷 h峄搃 l峄梚 th瓢峄漬g ch峄゛ `quotaResetDelay` trong tr瓢峄漬g `details`.

**V铆 d峄?l峄梚 429:**
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

### 2. Ph岷 h峄搃 tr峄憂g (M么 h矛nh b峄?h岷 ch岷?

M峄檛 s峄?m么 h矛nh c贸 th峄?xu岷 hi峄噉 trong danh s谩ch m么 h矛nh kh岷?d峄g nh瓢ng tr岷?v峄?ph岷 h峄搃 tr峄憂g (200 OK nh瓢ng lu峄搉g SSE tr峄憂g). 膼i峄乽 n脿y th瓢峄漬g x岷 ra v峄沬 c谩c m么 h矛nh xem tr瓢峄沜 ho岷穋 b峄?h岷 ch岷?m脿 d峄?谩n hi峄噉 t岷 kh么ng c贸 quy峄乶 s峄?d峄g.

**C谩ch x峄?l媒:** Coi ph岷 h峄搃 tr峄憂g l脿 l峄梚, th么ng b谩o cho ng瓢峄漣 d霉ng r岷眓g m么 h矛nh c贸 th峄?b峄?h岷 ch岷?ho岷穋 kh么ng h峄 l峄?cho d峄?谩n c峄 h峄?

---

## Kh岷痗 ph峄 s峄?c峄?
### "Token expired" (Token 膽茫 h岷縯 h岷)
- L脿m m峄沬 token OAuth: `picoclaw auth login --provider antigravity`

### "Gemini for Google Cloud is not enabled" (Gemini for Google Cloud ch瓢a 膽瓢峄 b岷璽)
- B岷璽 API trong Google Cloud Console c峄 b岷

### "Project not found" (Kh么ng t矛m th岷 d峄?谩n)
- 膼岷 b岷 d峄?谩n Google Cloud c峄 b岷 膽茫 b岷璽 c谩c API c岷 thi岷縯
- Ki峄僲 tra xem ID d峄?谩n c贸 膽瓢峄 l岷 ch铆nh x谩c trong qu谩 tr矛nh x谩c th峄眂 kh么ng

### M么 h矛nh kh么ng xu岷 hi峄噉 trong danh s谩ch
- X谩c minh x谩c th峄眂 OAuth 膽茫 ho脿n t岷 th脿nh c么ng
- Ki峄僲 tra l瓢u tr峄?h峄?s啤 x谩c th峄眂: `~/.picoclaw/auth.json`
- Ch岷 l岷 `picoclaw auth login --provider antigravity`
