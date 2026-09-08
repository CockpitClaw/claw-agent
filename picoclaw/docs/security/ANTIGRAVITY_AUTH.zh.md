> 杩斿洖 [README](../project/README.zh.md)

# Antigravity 璁よ瘉涓庨泦鎴愭寚鍗?
## 姒傝堪

**Antigravity**锛圙oogle Cloud Code Assist锛夋槸鐢?Google 鏀寔鐨?AI 妯″瀷鎻愪緵鍟嗭紝閫氳繃 Google 鐨勪簯鍩虹璁炬柦鎻愪緵瀵?Claude Opus 4.6 鍜?Gemini 绛夋ā鍨嬬殑璁块棶銆傛湰鏂囨。鎻愪緵浜嗗叧浜庤璇佸伐浣滃師鐞嗐€佸浣曡幏鍙栨ā鍨嬩互鍙婂浣曞湪 PicoClaw 涓疄鐜版柊鎻愪緵鍟嗙殑瀹屾暣鎸囧崡銆?
---

## 鐩綍

1. [璁よ瘉娴佺▼](#璁よ瘉娴佺▼)
2. [OAuth 瀹炵幇缁嗚妭](#oauth-瀹炵幇缁嗚妭)
3. [浠ょ墝绠＄悊](#浠ょ墝绠＄悊)
4. [妯″瀷鍒楄〃鑾峰彇](#妯″瀷鍒楄〃鑾峰彇)
5. [鐢ㄩ噺杩借釜](#鐢ㄩ噺杩借釜)
6. [鎻愪緵鍟嗘彃浠剁粨鏋刔(#鎻愪緵鍟嗘彃浠剁粨鏋?
7. [闆嗘垚瑕佹眰](#闆嗘垚瑕佹眰)
8. [API 绔偣](#api-绔偣)
9. [閰嶇疆](#閰嶇疆)
10. [鍦?PicoClaw 涓垱寤烘柊鎻愪緵鍟哴(#鍦?picoclaw-涓垱寤烘柊鎻愪緵鍟?

---

## 璁よ瘉娴佺▼

### 1. 甯?PKCE 鐨?OAuth 2.0

Antigravity 浣跨敤 **OAuth 2.0 with PKCE锛圥roof Key for Code Exchange锛?* 杩涜瀹夊叏璁よ瘉锛?
```
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                   鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?  Client    鈹?鈹€鈹€鈹€(1) Generate PKCE Pair鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€> 鈹?                鈹?鈹?            鈹?鈹€鈹€鈹€(2) Open Auth URL鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€> 鈹? Google OAuth   鈹?鈹?            鈹?                                   鈹?   Server       鈹?鈹?            鈹?<鈹€鈹€(3) Redirect with Code鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€ 鈹?                鈹?鈹?            鈹?                                   鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?            鈹?鈹€鈹€鈹€(4) Exchange Code for Tokens鈹€鈹€> 鈹?  Token URL     鈹?鈹?            鈹?                                   鈹?                鈹?鈹?            鈹?<鈹€鈹€(5) Access + Refresh Tokens鈹€鈹€鈹€鈹€ 鈹?                鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                   鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?```

### 2. 璇︾粏姝ラ

#### 姝ラ 1锛氱敓鎴?PKCE 鍙傛暟
```typescript
function generatePkce(): { verifier: string; challenge: string } {
  const verifier = randomBytes(32).toString("hex");
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  return { verifier, challenge };
}
```

#### 姝ラ 2锛氭瀯寤烘巿鏉?URL
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

**鎵€闇€鏉冮檺鑼冨洿锛?*
```typescript
const SCOPES = [
  "https://www.googleapis.com/auth/cloud-platform",
  "https://www.googleapis.com/auth/userinfo.email",
  "https://www.googleapis.com/auth/userinfo.profile",
  "https://www.googleapis.com/auth/cclog",
  "https://www.googleapis.com/auth/experimentsandconfigs",
];
```

#### 姝ラ 3锛氬鐞?OAuth 鍥炶皟

**鑷姩妯″紡锛堟湰鍦板紑鍙戯級锛?*
- 鍦ㄧ鍙?51121 涓婂惎鍔ㄦ湰鍦?HTTP 鏈嶅姟鍣?- 绛夊緟鏉ヨ嚜 Google 鐨勯噸瀹氬悜
- 浠庢煡璇㈠弬鏁颁腑鎻愬彇鎺堟潈鐮?
**鎵嬪姩妯″紡锛堣繙绋?鏃犲ご鐜锛夛細**
- 鍚戠敤鎴锋樉绀烘巿鏉?URL
- 鐢ㄦ埛鍦ㄦ祻瑙堝櫒涓畬鎴愯璇?- 鐢ㄦ埛灏嗗畬鏁寸殑閲嶅畾鍚?URL 绮樿创鍥炵粓绔?- 浠庣矘璐寸殑 URL 涓В鏋愭巿鏉冪爜

#### 姝ラ 4锛氱敤鎺堟潈鐮佷氦鎹护鐗?```typescript
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

#### 姝ラ 5锛氳幏鍙栭澶栫殑鐢ㄦ埛鏁版嵁

**鐢ㄦ埛閭锛?*
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

**椤圭洰 ID锛圓PI 璋冪敤蹇呴渶锛夛細**
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
  return data.cloudaicompanionProject || "rising-fact-p41fc"; // 榛樿鍥為€€鍊?}
```

---

## OAuth 瀹炵幇缁嗚妭

### 瀹㈡埛绔嚟鎹?
**閲嶈锛?* 杩欎簺鍑嵁鍦ㄦ簮浠ｇ爜涓互 base64 缂栫爜瀛樺偍锛岀敤浜庝笌 pi-ai 鍚屾锛?
```typescript
const decode = (s: string) => Buffer.from(s, "base64").toString();

const CLIENT_ID = decode(
  "WU9VUl9HT09HTEVfQ0xJRU5UX0lE"
);
const CLIENT_SECRET = decode("WU9VUl9HT09HTEVfQ0xJRU5UX1NFQ1JFVA==");
```

### OAuth 娴佺▼妯″紡

1. **鑷姩娴佺▼**锛堟湁娴忚鍣ㄧ殑鏈湴鏈哄櫒锛夛細
   - 鑷姩鎵撳紑娴忚鍣?   - 鏈湴鍥炶皟鏈嶅姟鍣ㄦ崟鑾烽噸瀹氬悜
   - 鍒濆璁よ瘉鍚庢棤闇€鐢ㄦ埛浜や簰

2. **鎵嬪姩娴佺▼**锛堣繙绋?鏃犲ご/WSL2 鐜锛夛細
   - 鏄剧ず URL 渚涙墜鍔ㄥ鍒剁矘璐?   - 鐢ㄦ埛鍦ㄥ閮ㄦ祻瑙堝櫒涓畬鎴愯璇?   - 鐢ㄦ埛灏嗗畬鏁寸殑閲嶅畾鍚?URL 绮樿创鍥炴潵

```typescript
function shouldUseManualOAuthFlow(isRemote: boolean): boolean {
  return isRemote || isWSL2Sync();
}
```

---

## 浠ょ墝绠＄悊

### 璁よ瘉閰嶇疆鏂囦欢缁撴瀯

```typescript
type OAuthCredential = {
  type: "oauth";
  provider: "google-antigravity";
  access: string;           // 璁块棶浠ょ墝
  refresh: string;          // 鍒锋柊浠ょ墝
  expires: number;          // 杩囨湡鏃堕棿鎴筹紙姣锛岃嚜 epoch 璧凤級
  email?: string;           // 鐢ㄦ埛閭
  projectId?: string;       // Google Cloud 椤圭洰 ID
};
```

### 浠ょ墝鍒锋柊

鍑嵁鍖呭惈涓€涓埛鏂颁护鐗岋紝鍙湪褰撳墠璁块棶浠ょ墝杩囨湡鏃剁敤浜庤幏鍙栨柊鐨勮闂护鐗屻€傝繃鏈熸椂闂磋缃簡 5 鍒嗛挓鐨勭紦鍐插尯浠ラ槻姝㈢珵鎬佹潯浠躲€?
---

## 妯″瀷鍒楄〃鑾峰彇

### 鑾峰彇鍙敤妯″瀷

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
  
  // 杩斿洖甯︽湁閰嶉淇℃伅鐨勬ā鍨?  return Object.entries(data.models).map(([modelId, modelInfo]) => ({
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

### 鍝嶅簲鏍煎紡

```typescript
type FetchAvailableModelsResponse = {
  models?: Record<string, {
    displayName?: string;
    quotaInfo?: {
      remainingFraction?: number | string;
      resetTime?: string;      // ISO 8601 鏃堕棿鎴?      isExhausted?: boolean;
    };
  }>;
};
```

---

## 鐢ㄩ噺杩借釜

### 鑾峰彇鐢ㄩ噺鏁版嵁

```typescript
export async function fetchAntigravityUsage(
  token: string,
  timeoutMs: number
): Promise<ProviderUsageSnapshot> {
  // 1. 鑾峰彇棰濆害鍜岃鍒掍俊鎭?  const loadCodeAssistRes = await fetch(
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

  // 鎻愬彇棰濆害淇℃伅
  const { availablePromptCredits, planInfo, currentTier } = data;
  
  // 2. 鑾峰彇妯″瀷閰嶉
  const modelsRes = await fetch(
    `${BASE_URL}/v1internal:fetchAvailableModels`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: JSON.stringify({ project: projectId }),
    }
  );

  // 鏋勫缓鐢ㄩ噺绐楀彛
  return {
    provider: "google-antigravity",
    displayName: "Google Antigravity",
    windows: [
      { label: "Credits", usedPercent: calculateUsedPercent(available, monthly) },
      // 鍚勬ā鍨嬮厤棰?..
    ],
    plan: currentTier?.name || planType,
  };
}
```

### 鐢ㄩ噺鍝嶅簲缁撴瀯

```typescript
type ProviderUsageSnapshot = {
  provider: "google-antigravity";
  displayName: string;
  windows: UsageWindow[];
  plan?: string;
  error?: string;
};

type UsageWindow = {
  label: string;           // "Credits" 鎴栨ā鍨?ID
  usedPercent: number;     // 0-100
  resetAt?: number;        // 閰嶉閲嶇疆鐨勬椂闂存埑
};
```

---

## 鎻愪緵鍟嗘彃浠剁粨鏋?
### 鎻掍欢瀹氫箟

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
            // OAuth 瀹炵幇鍦ㄦ澶?          },
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
  prompter: WizardPrompter;      // UI 鎻愮ず/閫氱煡
  runtime: RuntimeEnv;           // 鏃ュ織绛?  isRemote: boolean;             // 鏄惁鍦ㄨ繙绋嬭繍琛?  openUrl: (url: string) => Promise<void>;  // 娴忚鍣ㄦ墦寮€鍣?  oauth: {
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

## 闆嗘垚瑕佹眰

### 1. 鎵€闇€鐜/渚濊禆

- Go 鈮?1.25
- PicoClaw 浠ｇ爜搴擄紙`pkg/providers/` 鍜?`pkg/auth/`锛?- `crypto` 鍜?`net/http` 鏍囧噯搴撳寘

### 2. API 璋冪敤鎵€闇€鐨勮姹傚ご

```typescript
const REQUIRED_HEADERS = {
  "Authorization": `Bearer ${accessToken}`,
  "Content-Type": "application/json",
  "User-Agent": "antigravity",  // 鎴?"google-api-nodejs-client/9.15.1"
  "X-Goog-Api-Client": "google-cloud-sdk vscode_cloudshelleditor/0.1",
};

// 瀵逛簬 loadCodeAssist 璋冪敤锛岃繕闇€鍖呭惈锛?const CLIENT_METADATA = {
  ideType: "ANTIGRAVITY",  // 鎴?"IDE_UNSPECIFIED"
  platform: "PLATFORM_UNSPECIFIED",
  pluginType: "GEMINI",
};
```

### 3. 妯″瀷 Schema 娓呯悊

Antigravity 浣跨敤鍏煎 Gemini 鐨勬ā鍨嬶紝鍥犳宸ュ叿 schema 蹇呴』杩涜娓呯悊锛?
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

// 鍙戦€佸墠娓呯悊 schema
function cleanToolSchemaForGemini(schema: Record<string, unknown>): unknown {
  // 绉婚櫎涓嶆敮鎸佺殑鍏抽敭瀛?  // 纭繚椤跺眰鏈?type: "object"
  // 灞曞钩 anyOf/oneOf 鑱斿悎绫诲瀷
}
```

### 4. 鎬濈淮鍧楀鐞嗭紙Claude 妯″瀷锛?
瀵逛簬 Antigravity 鐨?Claude 妯″瀷锛屾€濈淮鍧楅渶瑕佺壒娈婂鐞嗭細

```typescript
const ANTIGRAVITY_SIGNATURE_RE = /^[A-Za-z0-9+/]+={0,2}$/;

export function sanitizeAntigravityThinkingBlocks(
  messages: AgentMessage[]
): AgentMessage[] {
  // 楠岃瘉鎬濈淮绛惧悕
  // 瑙勮寖鍖栫鍚嶅瓧娈?  // 涓㈠純鏈鍚嶇殑鎬濈淮鍧?}
```

---

## API 绔偣

### 璁よ瘉绔偣

| 绔偣 | 鏂规硶 | 鐢ㄩ€?|
|------|------|------|
| `https://accounts.google.com/o/oauth2/v2/auth` | GET | OAuth 鎺堟潈 |
| `https://oauth2.googleapis.com/token` | POST | 浠ょ墝浜ゆ崲 |
| `https://www.googleapis.com/oauth2/v1/userinfo` | GET | 鐢ㄦ埛淇℃伅锛堥偖绠憋級 |

### Cloud Code Assist 绔偣

| 绔偣 | 鏂规硶 | 鐢ㄩ€?|
|------|------|------|
| `https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist` | POST | 鍔犺浇椤圭洰淇℃伅銆侀搴︺€佽鍒?|
| `https://cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels` | POST | 鍒楀嚭鍙敤妯″瀷鍙婇厤棰?|
| `https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse` | POST | 鑱婂ぉ娴佸紡绔偣 |

**API 璇锋眰鏍煎紡锛堣亰澶╋級锛?*
`v1internal:streamGenerateContent` 绔偣鏈熸湜涓€涓寘瑁呮爣鍑?Gemini 璇锋眰鐨勪俊灏佹牸寮忥細

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

**API 鍝嶅簲鏍煎紡锛圫SE锛夛細**
姣忔潯 SSE 娑堟伅锛坄data: {...}`锛夎鍖呰鍦?`response` 瀛楁涓細

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

## 閰嶇疆

### config.json 閰嶇疆

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

### 璁よ瘉閰嶇疆鏂囦欢瀛樺偍

璁よ瘉閰嶇疆鏂囦欢瀛樺偍鍦?`~/.picoclaw/auth.json` 涓細

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

## 鍦?PicoClaw 涓垱寤烘柊鎻愪緵鍟?
PicoClaw 鎻愪緵鍟嗕互 Go 鍖呯殑褰㈠紡瀹炵幇锛屼綅浜?`pkg/providers/` 涓嬨€傝娣诲姞鏂版彁渚涘晢锛?
### 鍒嗘瀹炵幇

#### 1. 鍒涘缓鎻愪緵鍟嗘枃浠?
鍦?`pkg/providers/` 涓垱寤烘柊鐨?Go 鏂囦欢锛?
```
pkg/providers/
鈹斺攢鈹€ your_provider.go
```

#### 2. 瀹炵幇 Provider 鎺ュ彛

浣犵殑鎻愪緵鍟嗗繀椤诲疄鐜?`pkg/providers/types.go` 涓畾涔夌殑 `Provider` 鎺ュ彛锛?
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
    // 瀹炵幇甯︽祦寮忎紶杈撶殑鑱婂ぉ琛ュ叏
}
```

#### 3. 鍦ㄥ伐鍘備腑娉ㄥ唽

灏嗕綘鐨勬彁渚涘晢娣诲姞鍒?`pkg/providers/factory.go` 涓殑鍗忚鍒嗘敮锛?
```go
case "your-provider":
    return NewYourProvider(sel.apiKey, sel.apiBase, sel.proxy), nil
```

#### 4. 娣诲姞榛樿閰嶇疆锛堝彲閫夛級

鍦?`pkg/config/defaults.go` 涓坊鍔犻粯璁ゆ潯鐩細

```go
{
    ModelName: "your-model",
    Model:     "your-provider/model-name",
    APIKey:    "",
},
```

#### 5. 娣诲姞璁よ瘉鏀寔锛堝彲閫夛級

濡傛灉浣犵殑鎻愪緵鍟嗛渶瑕?OAuth 鎴栫壒娈婅璇侊紝鍦?`cmd/picoclaw/internal/auth/helpers.go` 涓坊鍔犲垎鏀細

```go
case "your-provider":
    authLoginYourProvider()
```

#### 6. 閫氳繃 `config.json` 閰嶇疆

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

## 娴嬭瘯浣犵殑瀹炵幇

### CLI 鍛戒护

```bash
# 浣跨敤鎻愪緵鍟嗚繘琛岃璇?picoclaw auth login --provider your-provider

# 鍒楀嚭妯″瀷锛堢敤浜?Antigravity锛?picoclaw auth models

# 鍚姩缃戝叧
picoclaw gateway

# 浣跨敤鎸囧畾妯″瀷杩愯浠ｇ悊
picoclaw agent -m "Hello" --model your-model
```

### 娴嬭瘯鐢ㄧ幆澧冨彉閲?
```bash
# 瑕嗙洊榛樿妯″瀷
export PICOCLAW_AGENTS_DEFAULTS_MODEL=your-model

# 瑕嗙洊鎻愪緵鍟嗚缃?export PICOCLAW_MODEL_LIST='[{"model_name":"your-model","model":"your-provider/model-name","api_keys":["..."]}]'
```

---

## 鍙傝€冭祫鏂?
- **婧愭枃浠讹細**
  - `pkg/providers/antigravity_provider.go` - Antigravity 鎻愪緵鍟嗗疄鐜?  - `pkg/auth/oauth.go` - OAuth 娴佺▼瀹炵幇
  - `pkg/auth/store.go` - 璁よ瘉鍑嵁瀛樺偍锛坄~/.picoclaw/auth.json`锛?  - `pkg/providers/factory.go` - 鎻愪緵鍟嗗伐鍘傚拰鍗忚璺敱
  - `pkg/providers/types.go` - 鎻愪緵鍟嗘帴鍙ｅ畾涔?  - `cmd/picoclaw/internal/auth/helpers.go` - 璁よ瘉 CLI 鍛戒护

- **鏂囨。锛?*
  - `docs/ANTIGRAVITY_USAGE.md` - Antigravity 浣跨敤鎸囧崡
  - `docs/migration/model-list-migration.md` - 杩佺Щ鎸囧崡

---

## 娉ㄦ剰浜嬮」

1. **Google Cloud 椤圭洰锛?* Antigravity 瑕佹眰鍦ㄤ綘鐨?Google Cloud 椤圭洰涓婂惎鐢?Gemini for Google Cloud
2. **閰嶉锛?* 浣跨敤 Google Cloud 椤圭洰閰嶉锛堥潪鐙珛璁¤垂锛?3. **妯″瀷璁块棶锛?* 鍙敤妯″瀷鍙栧喅浜庝綘鐨?Google Cloud 椤圭洰閰嶇疆
4. **鎬濈淮鍧楋細** 閫氳繃 Antigravity 浣跨敤鐨?Claude 妯″瀷闇€瑕佸甯︾鍚嶇殑鎬濈淮鍧楄繘琛岀壒娈婂鐞?5. **Schema 娓呯悊锛?* 宸ュ叿 schema 蹇呴』娓呯悊浠ョЩ闄や笉鏀寔鐨?JSON Schema 鍏抽敭瀛?
---

---

## 甯歌閿欒澶勭悊

### 1. 閫熺巼闄愬埗锛圚TTP 429锛?
褰撻」鐩?妯″瀷閰嶉鑰楀敖鏃讹紝Antigravity 浼氳繑鍥?429 閿欒銆傞敊璇搷搴旈€氬父鍦?`details` 瀛楁涓寘鍚?`quotaResetDelay`銆?
**429 閿欒绀轰緥锛?*
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

### 2. 绌哄搷搴旓紙鍙楅檺妯″瀷锛?
鏌愪簺妯″瀷鍙兘鍑虹幇鍦ㄥ彲鐢ㄦā鍨嬪垪琛ㄤ腑锛屼絾杩斿洖绌哄搷搴旓紙200 OK 浣?SSE 娴佷负绌猴級銆傝繖閫氬父鍙戠敓鍦ㄥ綋鍓嶉」鐩病鏈夋潈闄愪娇鐢ㄧ殑棰勮鐗堟垨鍙楅檺妯″瀷涓娿€?
**澶勭悊鏂瑰紡锛?* 灏嗙┖鍝嶅簲瑙嗕负閿欒锛岄€氱煡鐢ㄦ埛璇ユā鍨嬪彲鑳藉鍏堕」鐩彈闄愭垨鏃犳晥銆?
---

## 鏁呴殰鎺掗櫎

### "Token expired"锛堜护鐗屽凡杩囨湡锛?- 鍒锋柊 OAuth 浠ょ墝锛歚picoclaw auth login --provider antigravity`

### "Gemini for Google Cloud is not enabled"锛圙emini for Google Cloud 鏈惎鐢級
- 鍦?Google Cloud Console 涓惎鐢ㄨ API

### "Project not found"锛堥」鐩湭鎵惧埌锛?- 纭繚浣犵殑 Google Cloud 椤圭洰宸插惎鐢ㄥ繀瑕佺殑 API
- 妫€鏌ヨ璇佽繃绋嬩腑椤圭洰 ID 鏄惁姝ｇ‘鑾峰彇

### 妯″瀷鏈嚭鐜板湪鍒楄〃涓?- 楠岃瘉 OAuth 璁よ瘉鏄惁鎴愬姛瀹屾垚
- 妫€鏌ヨ璇侀厤缃枃浠跺瓨鍌細`~/.picoclaw/auth.json`
- 閲嶆柊杩愯 `picoclaw auth login --provider antigravity`
