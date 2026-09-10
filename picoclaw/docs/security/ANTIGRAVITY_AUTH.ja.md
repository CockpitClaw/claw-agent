> [README](../project/README.ja.md) 銇埢銈?
# Antigravity 瑾嶈銉荤当鍚堛偓銈ゃ儔

## 姒傝

**Antigravity**锛圙oogle Cloud Code Assist锛夈伅銆丟oogle 銇屾彁渚涖仚銈?AI 銉儑銉儣銉儛銈ゃ儉銉笺仹銆丟oogle 銇偗銉┿偊銉夈偆銉炽儠銉┿偣銉堛儵銈儊銉ｃ倰閫氥仒銇?Claude Opus 4.6 銈?Gemini 銇仼銇儮銉囥儷銇搞伄銈偗銈汇偣銈掓彁渚涖仐銇俱仚銆傛湰銉夈偔銉ャ儭銉炽儓銇с伅銆佽獚瑷笺伄浠曠祫銇裤€併儮銉囥儷銇彇寰楁柟娉曘€丳icoClaw 銇с伄鏂般仐銇勩儣銉儛銈ゃ儉銉笺伄瀹熻鏂规硶銇仱銇勩仸瀹屽叏銇偓銈ゃ儔銈掓彁渚涖仐銇俱仚銆?
---

## 鐩

1. [瑾嶈銉曘儹銉糫(#瑾嶈銉曘儹銉?
2. [OAuth 瀹熻銇┏绱癩(#oauth-瀹熻銇┏绱?
3. [銉堛兗銈兂绠＄悊](#銉堛兗銈兂绠＄悊)
4. [銉儑銉儶銈广儓銇彇寰梋(#銉儑銉儶銈广儓銇彇寰?
5. [浣跨敤閲忋儓銉┿儍銈兂銈癩(#浣跨敤閲忋儓銉┿儍銈兂銈?
6. [銉椼儹銉愩偆銉€銉笺儣銉┿偘銈ゃ兂妲嬮€燷(#銉椼儹銉愩偆銉€銉笺儣銉┿偘銈ゃ兂妲嬮€?
7. [绲卞悎瑕佷欢](#绲卞悎瑕佷欢)
8. [API 銈ㄣ兂銉夈儩銈ゃ兂銉圿(#api-銈ㄣ兂銉夈儩銈ゃ兂銉?
9. [瑷畾](#瑷畾)
10. [PicoClaw 銇с伄鏂般仐銇勩儣銉儛銈ゃ儉銉笺伄浣滄垚](#picoclaw-銇с伄鏂般仐銇勩儣銉儛銈ゃ儉銉笺伄浣滄垚)

---

## 瑾嶈銉曘儹銉?
### 1. PKCE 浠樸亶 OAuth 2.0

Antigravity 銇偦銈儱銈仾瑾嶈銇仧銈併伀 **OAuth 2.0 with PKCE锛圥roof Key for Code Exchange锛?* 銈掍娇鐢ㄣ仐銇俱仚锛?
```
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                   鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?  Client    鈹?鈹€鈹€鈹€(1) Generate PKCE Pair鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€> 鈹?                鈹?鈹?            鈹?鈹€鈹€鈹€(2) Open Auth URL鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€> 鈹? Google OAuth   鈹?鈹?            鈹?                                   鈹?   Server       鈹?鈹?            鈹?<鈹€鈹€(3) Redirect with Code鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€ 鈹?                鈹?鈹?            鈹?                                   鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?            鈹?鈹€鈹€鈹€(4) Exchange Code for Tokens鈹€鈹€> 鈹?  Token URL     鈹?鈹?            鈹?                                   鈹?                鈹?鈹?            鈹?<鈹€鈹€(5) Access + Refresh Tokens鈹€鈹€鈹€鈹€ 鈹?                鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                   鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?```

### 2. 瑭崇窗鎵嬮爢

#### 銈广儐銉冦儣 1锛歅KCE 銉戙儵銉°兗銈裤伄鐢熸垚
```typescript
function generatePkce(): { verifier: string; challenge: string } {
  const verifier = randomBytes(32).toString("hex");
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  return { verifier, challenge };
}
```

#### 銈广儐銉冦儣 2锛氳獚鍙?URL 銇绡?```typescript
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

**蹇呰銇偣銈炽兗銉楋細**
```typescript
const SCOPES = [
  "https://www.googleapis.com/auth/cloud-platform",
  "https://www.googleapis.com/auth/userinfo.email",
  "https://www.googleapis.com/auth/userinfo.profile",
  "https://www.googleapis.com/auth/cclog",
  "https://www.googleapis.com/auth/experimentsandconfigs",
];
```

#### 銈广儐銉冦儣 3锛歄Auth 銈炽兗銉儛銉冦偗銇嚘鐞?
**鑷嫊銉兗銉夛紙銉兗銈儷闁嬬櫤锛夛細**
- 銉濄兗銉?51121 銇с儹銉笺偒銉?HTTP 銈点兗銉愩兗銈掕捣鍕?- Google 銇嬨倝銇儶銉€銈ゃ儸銈儓銈掑緟姗?- 銈偍銉儜銉┿儭銉笺偪銇嬨倝瑾嶅彲銈炽兗銉夈倰鎶藉嚭

**鎵嬪嫊銉兗銉夛紙銉儮銉笺儓/銉樸儍銉夈儸銈癸級锛?*
- 銉︺兗銈躲兗銇獚鍙?URL 銈掕〃绀?- 銉︺兗銈躲兗銇屻儢銉┿偊銈躲仹瑾嶈銈掑畬浜?- 銉︺兗銈躲兗銇屽畬鍏ㄣ仾銉儉銈ゃ儸銈儓 URL 銈掋偪銉笺儫銉娿儷銇布銈婁粯銇?- 璨笺倞浠樸亼銈夈倢銇?URL 銇嬨倝銈炽兗銉夈倰瑙ｆ瀽

#### 銈广儐銉冦儣 4锛氥偝銉笺儔銈掋儓銉笺偗銉炽伀浜ゆ彌
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

#### 銈广儐銉冦儣 5锛氳拷鍔犮伄銉︺兗銈躲兗銉囥兗銈裤伄鍙栧緱

**銉︺兗銈躲兗銉°兗銉細**
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

**銉椼儹銈搞偋銈儓 ID锛圓PI 鍛笺伋鍑恒仐銇繀闋堬級锛?*
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
  return data.cloudaicompanionProject || "rising-fact-p41fc"; // 銉囥儠銈┿儷銉堛伄銉曘偐銉笺儷銉愩儍銈?}
```

---

## OAuth 瀹熻銇┏绱?
### 銈儵銈ゃ偄銉炽儓瑾嶈鎯呭牨

**閲嶈锛?* 銇撱倢銈夈伅 pi-ai 銇ㄣ伄鍚屾湡銇仧銈併伀銈姐兗銈广偝銉笺儔鍐呫仹 base64 銈ㄣ兂銈炽兗銉夈仌銈屻仸銇勩伨銇欙細

```typescript
const decode = (s: string) => Buffer.from(s, "base64").toString();

const CLIENT_ID = process.env.ANTIGRAVITY_OAUTH_CLIENT_ID || "";
const CLIENT_SECRET = process.env.ANTIGRAVITY_OAUTH_CLIENT_SECRET || "";
```

### OAuth 銉曘儹銉笺儮銉笺儔

1. **鑷嫊銉曘儹銉?*锛堛儢銉┿偊銈躲伄銇傘倠銉兗銈儷銉炪偡銉筹級锛?   - 銉栥儵銈︺偠銈掕嚜鍕曠殑銇枊銇?   - 銉兗銈儷銈炽兗銉儛銉冦偗銈点兗銉愩兗銇屻儶銉€銈ゃ儸銈儓銈掋偔銉ｃ儣銉併儯
   - 鍒濆洖瑾嶈寰屻伅銉︺兗銈躲兗鎿嶄綔涓嶈

2. **鎵嬪嫊銉曘儹銉?*锛堛儶銉兗銉?銉樸儍銉夈儸銈?WSL2锛夛細
   - 鎵嬪嫊銈炽償銉硷紗銉氥兗銈广儓鐢ㄣ伄 URL 銈掕〃绀?   - 銉︺兗銈躲兗銇屽閮ㄣ儢銉┿偊銈躲仹瑾嶈銈掑畬浜?   - 銉︺兗銈躲兗銇屽畬鍏ㄣ仾銉儉銈ゃ儸銈儓 URL 銈掕布銈婁粯銇?
```typescript
function shouldUseManualOAuthFlow(isRemote: boolean): boolean {
  return isRemote || isWSL2Sync();
}
```

---

## 銉堛兗銈兂绠＄悊

### 瑾嶈銉椼儹銉曘偂銈ゃ儷妲嬮€?
```typescript
type OAuthCredential = {
  type: "oauth";
  provider: "google-antigravity";
  access: string;           // 銈偗銈汇偣銉堛兗銈兂
  refresh: string;          // 銉儠銉儍銈枫儱銉堛兗銈兂
  expires: number;          // 鏈夊姽鏈熼檺銈裤偆銉犮偣銈裤兂銉楋紙銈ㄣ儩銉冦偗銇嬨倝銇儫銉锛?  email?: string;           // 銉︺兗銈躲兗銉°兗銉?  projectId?: string;       // Google Cloud 銉椼儹銈搞偋銈儓 ID
};
```

### 銉堛兗銈兂銇洿鏂?
瑾嶈鎯呭牨銇伅銉儠銉儍銈枫儱銉堛兗銈兂銇屽惈銇俱倢銇︺亰銈娿€佺従鍦ㄣ伄銈偗銈汇偣銉堛兗銈兂銇屾湡闄愬垏銈屻伀銇仯銇熼殯銇柊銇椼亜銈偗銈汇偣銉堛兗銈兂銈掑彇寰椼仚銈嬨仧銈併伀浣跨敤銇с亶銇俱仚銆傛湁鍔规湡闄愩伅绔跺悎鐘舵厠銈掗槻銇愩仧銈併伀 5 鍒嗐伄銉愩儍銉曘偂銈掕ō銇戙仸銇勩伨銇欍€?
---

## 銉儑銉儶銈广儓銇彇寰?
### 鍒╃敤鍙兘銇儮銉囥儷銇彇寰?
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
  
  // 銈偐銉笺偪鎯呭牨浠樸亶銇儮銉囥儷銈掕繑銇?  return Object.entries(data.models).map(([modelId, modelInfo]) => ({
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

### 銉偣銉濄兂銈瑰舰寮?
```typescript
type FetchAvailableModelsResponse = {
  models?: Record<string, {
    displayName?: string;
    quotaInfo?: {
      remainingFraction?: number | string;
      resetTime?: string;      // ISO 8601 銈裤偆銉犮偣銈裤兂銉?      isExhausted?: boolean;
    };
  }>;
};
```

---

## 浣跨敤閲忋儓銉┿儍銈兂銈?
### 浣跨敤閲忋儑銉笺偪銇彇寰?
```typescript
export async function fetchAntigravityUsage(
  token: string,
  timeoutMs: number
): Promise<ProviderUsageSnapshot> {
  // 1. 銈儸銈搞儍銉堛仺銉椼儵銉虫儏鍫便倰鍙栧緱
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

  // 銈儸銈搞儍銉堟儏鍫便倰鎶藉嚭
  const { availablePromptCredits, planInfo, currentTier } = data;
  
  // 2. 銉儑銉偗銈┿兗銈裤倰鍙栧緱
  const modelsRes = await fetch(
    `${BASE_URL}/v1internal:fetchAvailableModels`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: JSON.stringify({ project: projectId }),
    }
  );

  // 浣跨敤閲忋偊銈ｃ兂銉夈偊銈掓绡?  return {
    provider: "google-antigravity",
    displayName: "Google Antigravity",
    windows: [
      { label: "Credits", usedPercent: calculateUsedPercent(available, monthly) },
      // 鍊嬪垾銉儑銉偗銈┿兗銈?..
    ],
    plan: currentTier?.name || planType,
  };
}
```

### 浣跨敤閲忋儸銈广儩銉炽偣妲嬮€?
```typescript
type ProviderUsageSnapshot = {
  provider: "google-antigravity";
  displayName: string;
  windows: UsageWindow[];
  plan?: string;
  error?: string;
};

type UsageWindow = {
  label: string;           // "Credits" 銇俱仧銇儮銉囥儷 ID
  usedPercent: number;     // 0-100
  resetAt?: number;        // 銈偐銉笺偪銇屻儶銈汇儍銉堛仌銈屻倠銈裤偆銉犮偣銈裤兂銉?};
```

---

## 銉椼儹銉愩偆銉€銉笺儣銉┿偘銈ゃ兂妲嬮€?
### 銉椼儵銈般偆銉冲畾缇?
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
            // OAuth 瀹熻銇亾銇撱伀瑷樿堪
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
  prompter: WizardPrompter;      // UI 銉椼儹銉炽儣銉?閫氱煡
  runtime: RuntimeEnv;           // 銉偘銇仼
  isRemote: boolean;             // 銉儮銉笺儓瀹熻銇嬨仼銇嗐亱
  openUrl: (url: string) => Promise<void>;  // 銉栥儵銈︺偠銈兗銉椼儕銉?  oauth: {
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

## 绲卞悎瑕佷欢

### 1. 蹇呰銇挵澧?渚濆瓨闁總

- Go 鈮?1.25
- PicoClaw 銈炽兗銉夈儥銉笺偣锛坄pkg/providers/` 銇娿倛銇?`pkg/auth/`锛?- `crypto` 銇娿倛銇?`net/http` 妯欐簴銉┿偆銉栥儵銉儜銉冦偙銉笺偢

### 2. API 鍛笺伋鍑恒仐銇繀瑕併仾銉樸儍銉€銉?
```typescript
const REQUIRED_HEADERS = {
  "Authorization": `Bearer ${accessToken}`,
  "Content-Type": "application/json",
  "User-Agent": "antigravity",  // 銇俱仧銇?"google-api-nodejs-client/9.15.1"
  "X-Goog-Api-Client": "google-cloud-sdk vscode_cloudshelleditor/0.1",
};

// loadCodeAssist 鍛笺伋鍑恒仐銇伅浠ヤ笅銈傚惈銈併倠锛?const CLIENT_METADATA = {
  ideType: "ANTIGRAVITY",  // 銇俱仧銇?"IDE_UNSPECIFIED"
  platform: "PLATFORM_UNSPECIFIED",
  pluginType: "GEMINI",
};
```

### 3. 銉儑銉偣銈兗銉炪伄銈点儖銈裤偆銈?
Antigravity 銇?Gemini 浜掓彌銉儑銉倰浣跨敤銇欍倠銇熴倎銆併儎銉笺儷銈广偔銉笺優銇偟銉嬨偪銈ゃ偤銇屽繀瑕併仹銇欙細

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

// 閫佷俊鍓嶃伀銈广偔銉笺優銈掋偗銉兗銉炽偄銉冦儣
function cleanToolSchemaForGemini(schema: Record<string, unknown>): unknown {
  // 銈点儩銉笺儓銇曘倢銇︺亜銇亜銈兗銉兗銉夈倰鍓婇櫎
  // 銉堛儍銉椼儸銉欍儷銇?type: "object" 銇屻亗銈嬨亾銇ㄣ倰纰鸿獚
  // anyOf/oneOf 銉︺儖銈兂銈掋儠銉┿儍銉堝寲
}
```

### 4. 鎬濊€冦儢銉儍銈伄鍑︾悊锛圕laude 銉儑銉級

Antigravity 銇?Claude 銉儑銉仹銇€佹€濊€冦儢銉儍銈伀鐗瑰垾銇嚘鐞嗐亴蹇呰銇с仚锛?
```typescript
const ANTIGRAVITY_SIGNATURE_RE = /^[A-Za-z0-9+/]+={0,2}$/;

export function sanitizeAntigravityThinkingBlocks(
  messages: AgentMessage[]
): AgentMessage[] {
  // 鎬濊€冦偡銈般儘銉併儯銈掓瑷?  // 銈枫偘銉嶃儊銉ｃ儠銈ｃ兗銉儔銈掓瑕忓寲
  // 缃插悕銇曘倢銇︺亜銇亜鎬濊€冦儢銉儍銈倰鐮存
}
```

---

## API 銈ㄣ兂銉夈儩銈ゃ兂銉?
### 瑾嶈銈ㄣ兂銉夈儩銈ゃ兂銉?
| 銈ㄣ兂銉夈儩銈ゃ兂銉?| 銉°偨銉冦儔 | 鐢ㄩ€?|
|---------------|---------|------|
| `https://accounts.google.com/o/oauth2/v2/auth` | GET | OAuth 瑾嶅彲 |
| `https://oauth2.googleapis.com/token` | POST | 銉堛兗銈兂浜ゆ彌 |
| `https://www.googleapis.com/oauth2/v1/userinfo` | GET | 銉︺兗銈躲兗鎯呭牨锛堛儭銉笺儷锛?|

### Cloud Code Assist 銈ㄣ兂銉夈儩銈ゃ兂銉?
| 銈ㄣ兂銉夈儩銈ゃ兂銉?| 銉°偨銉冦儔 | 鐢ㄩ€?|
|---------------|---------|------|
| `https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist` | POST | 銉椼儹銈搞偋銈儓鎯呭牨銆併偗銉偢銉冦儓銆併儣銉┿兂銇銇胯炯銇?|
| `https://cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels` | POST | 銈偐銉笺偪浠樸亶鍒╃敤鍙兘銉儑銉伄涓€瑕?|
| `https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse` | POST | 銉併儯銉冦儓銈广儓銉兗銉熴兂銈般偍銉炽儔銉濄偆銉炽儓 |

**API 銉偗銈ㄣ偣銉堝舰寮忥紙銉併儯銉冦儓锛夛細**
`v1internal:streamGenerateContent` 銈ㄣ兂銉夈儩銈ゃ兂銉堛伅銆佹婧栥伄 Gemini 銉偗銈ㄣ偣銉堛倰銉┿儍銉椼仚銈嬨偍銉炽儥銉兗銉楀舰寮忋倰鏈熷緟銇椼伨銇欙細

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

**API 銉偣銉濄兂銈瑰舰寮忥紙SSE锛夛細**
鍚?SSE 銉°儍銈汇兗銈革紙`data: {...}`锛夈伅 `response` 銉曘偅銉笺儷銉夈仹銉┿儍銉椼仌銈屻伨銇欙細

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

## 瑷畾

### config.json 銇ō瀹?
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

### 瑾嶈銉椼儹銉曘偂銈ゃ儷銇繚瀛?
瑾嶈銉椼儹銉曘偂銈ゃ儷銇?`~/.picoclaw/auth.json` 銇繚瀛樸仌銈屻伨銇欙細

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

## PicoClaw 銇с伄鏂般仐銇勩儣銉儛銈ゃ儉銉笺伄浣滄垚

PicoClaw 銇儣銉儛銈ゃ儉銉笺伅 `pkg/providers/` 閰嶄笅銇?Go 銉戙儍銈便兗銈搞仺銇椼仸瀹熻銇曘倢銇俱仚銆傛柊銇椼亜銉椼儹銉愩偆銉€銉笺倰杩藉姞銇欍倠銇伅锛?
### 銈广儐銉冦儣銉愩偆銈广儐銉冦儣銇疅瑁?
#### 1. 銉椼儹銉愩偆銉€銉笺儠銈°偆銉伄浣滄垚

`pkg/providers/` 銇柊銇椼亜 Go 銉曘偂銈ゃ儷銈掍綔鎴愩仐銇俱仚锛?
```
pkg/providers/
鈹斺攢鈹€ your_provider.go
```

#### 2. Provider 銈ゃ兂銈裤兗銉曘偋銉笺偣銇疅瑁?
銉椼儹銉愩偆銉€銉笺伅 `pkg/providers/types.go` 銇у畾缇┿仌銈屻仧 `Provider` 銈ゃ兂銈裤兗銉曘偋銉笺偣銈掑疅瑁呫仚銈嬪繀瑕併亴銇傘倞銇俱仚锛?
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
    // 銈广儓銉兗銉熴兂銈颁粯銇嶃儊銉ｃ儍銉堣瀹屻倰瀹熻
}
```

#### 3. 銉曘偂銈儓銉兗銇搞伄鐧婚尣

`pkg/providers/factory.go` 銇儣銉儓銈炽儷銈广偆銉冦儊銇儣銉儛銈ゃ儉銉笺倰杩藉姞銇椼伨銇欙細

```go
case "your-provider":
    return NewYourProvider(sel.apiKey, sel.apiBase, sel.proxy), nil
```

#### 4. 銉囥儠銈┿儷銉堣ō瀹氥伄杩藉姞锛堛偑銉椼偡銉с兂锛?
`pkg/config/defaults.go` 銇儑銉曘偐銉儓銈ㄣ兂銉堛儶銈掕拷鍔犮仐銇俱仚锛?
```go
{
    ModelName: "your-model",
    Model:     "your-provider/model-name",
    APIKey:    "",
},
```

#### 5. 瑾嶈銈点儩銉笺儓銇拷鍔狅紙銈儣銈枫儳銉筹級

銉椼儹銉愩偆銉€銉笺亴 OAuth 銈勭壒鍒ャ仾瑾嶈銈掑繀瑕併仺銇欍倠鍫村悎銆乣cmd/picoclaw/internal/auth/helpers.go` 銇偙銉笺偣銈掕拷鍔犮仐銇俱仚锛?
```go
case "your-provider":
    authLoginYourProvider()
```

#### 6. `config.json` 銇с伄瑷畾

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

## 瀹熻銇儐銈广儓

### CLI 銈炽優銉炽儔

```bash
# 銉椼儹銉愩偆銉€銉笺仹瑾嶈
picoclaw auth login --provider your-provider

# 銉儑銉伄涓€瑕ц〃绀猴紙Antigravity 鐢級
picoclaw auth models

# 銈层兗銉堛偊銈с偆銇捣鍕?picoclaw gateway

# 鐗瑰畾銇儮銉囥儷銇с偍銉笺偢銈с兂銉堛倰瀹熻
picoclaw agent -m "Hello" --model your-model
```

### 銉嗐偣銉堢敤鐠板澶夋暟

```bash
# 銉囥儠銈┿儷銉堛儮銉囥儷銇笂鏇搞亶
export PICOCLAW_AGENTS_DEFAULTS_MODEL=your-model

# 銉椼儹銉愩偆銉€銉艰ō瀹氥伄涓婃浉銇?export PICOCLAW_MODEL_LIST='[{"model_name":"your-model","model":"your-provider/model-name","api_keys":["..."]}]'
```

---

## 鍙傝€冭硣鏂?
- **銈姐兗銈广儠銈°偆銉細**
  - `pkg/providers/antigravity_provider.go` - Antigravity 銉椼儹銉愩偆銉€銉煎疅瑁?  - `pkg/auth/oauth.go` - OAuth 銉曘儹銉煎疅瑁?  - `pkg/auth/store.go` - 瑾嶈鎯呭牨銈广儓銉兗銈革紙`~/.picoclaw/auth.json`锛?  - `pkg/providers/factory.go` - 銉椼儹銉愩偆銉€銉笺儠銈°偗銉堛儶銉笺仺銉椼儹銉堛偝銉儷銉笺儐銈ｃ兂銈?  - `pkg/providers/types.go` - 銉椼儹銉愩偆銉€銉笺偆銉炽偪銉笺儠銈с兗銈瑰畾缇?  - `cmd/picoclaw/internal/auth/helpers.go` - 瑾嶈 CLI 銈炽優銉炽儔

- **銉夈偔銉ャ儭銉炽儓锛?*
  - `docs/ANTIGRAVITY_USAGE.md` - Antigravity 浣跨敤銈偆銉?  - `docs/migration/model-list-migration.md` - 绉昏銈偆銉?
---

## 娉ㄦ剰浜嬮爡

1. **Google Cloud 銉椼儹銈搞偋銈儓锛?* Antigravity 銇?Google Cloud 銉椼儹銈搞偋銈儓銇?Gemini for Google Cloud 銇屾湁鍔广伀銇仯銇︺亜銈嬪繀瑕併亴銇傘倞銇俱仚
2. **銈偐銉笺偪锛?* Google Cloud 銉椼儹銈搞偋銈儓銇偗銈┿兗銈裤倰浣跨敤銇椼伨銇欙紙鍊嬪垾銇閲戙仹銇亗銈娿伨銇涖倱锛?3. **銉儑銉偄銈偦銈癸細** 鍒╃敤鍙兘銇儮銉囥儷銇?Google Cloud 銉椼儹銈搞偋銈儓銇ō瀹氥伀渚濆瓨銇椼伨銇?4. **鎬濊€冦儢銉儍銈細** Antigravity 绲岀敱銇?Claude 銉儑銉伅銆佺讲鍚嶄粯銇嶆€濊€冦儢銉儍銈伄鐗瑰垾銇嚘鐞嗐亴蹇呰銇с仚
5. **銈广偔銉笺優銈点儖銈裤偆銈猴細** 銉勩兗銉偣銈兗銉炪伅銈点儩銉笺儓銇曘倢銇︺亜銇亜 JSON Schema 銈兗銉兗銉夈倰鍓婇櫎銇欍倠銇熴倎銇偟銉嬨偪銈ゃ偤銇屽繀瑕併仹銇?
---

---

## 涓€鑸殑銇偍銉┿兗鍑︾悊

### 1. 銉兗銉堝埗闄愶紙HTTP 429锛?
銉椼儹銈搞偋銈儓/銉儑銉伄銈偐銉笺偪銇屾灟娓囥仚銈嬨仺銆丄ntigravity 銇?429 銈ㄣ儵銉笺倰杩斻仐銇俱仚銆傘偍銉┿兗銉偣銉濄兂銈广伀銇€氬父銆乣details` 銉曘偅銉笺儷銉夈伀 `quotaResetDelay` 銇屽惈銇俱倢銇俱仚銆?
**429 銈ㄣ儵銉笺伄渚嬶細**
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

### 2. 绌恒伄銉偣銉濄兂銈癸紙鍒堕檺浠樸亶銉儑銉級

涓€閮ㄣ伄銉儑銉伅鍒╃敤鍙兘銉儑銉儶銈广儓銇〃绀恒仌銈屻伨銇欍亴銆佺┖銇儸銈广儩銉炽偣銈掕繑銇欏牬鍚堛亴銇傘倞銇俱仚锛?00 OK 銇犮亴 SSE 銈广儓銉兗銉犮亴绌猴級銆傘亾銈屻伅閫氬父銆佺従鍦ㄣ伄銉椼儹銈搞偋銈儓銇娇鐢ㄦī闄愩亴銇亜銉椼儸銉撱儱銉肩増銇俱仧銇埗闄愪粯銇嶃儮銉囥儷銇х櫤鐢熴仐銇俱仚銆?
**瀵惧嚘娉曪細** 绌恒伄銉偣銉濄兂銈广倰銈ㄣ儵銉笺仺銇椼仸鎵便亜銆併仢銇儮銉囥儷銇屻儣銉偢銈с偗銉堛伀瀵俱仐銇﹀埗闄愩仌銈屻仸銇勩倠銇嬬劇鍔广仹銇傘倠鍙兘鎬с亴銇傘倠銇撱仺銈掋儲銉笺偠銉笺伀閫氱煡銇椼伨銇欍€?
---

## 銉堛儵銉栥儷銈枫儱銉笺儐銈ｃ兂銈?
### "Token expired"锛堛儓銉笺偗銉虫湡闄愬垏銈岋級
- OAuth 銉堛兗銈兂銈掓洿鏂帮細`picoclaw auth login --provider antigravity`

### "Gemini for Google Cloud is not enabled"锛圙emini for Google Cloud 銇屾湁鍔广伀銇仯銇︺亜銇亜锛?- Google Cloud Console 銇?API 銈掓湁鍔广伀銇椼仸銇忋仩銇曘亜

### "Project not found"锛堛儣銉偢銈с偗銉堛亴瑕嬨仱銇嬨倝銇亜锛?- Google Cloud 銉椼儹銈搞偋銈儓銇у繀瑕併仾 API 銇屾湁鍔广伀銇仯銇︺亜銈嬨亾銇ㄣ倰纰鸿獚銇椼仸銇忋仩銇曘亜
- 瑾嶈涓伀銉椼儹銈搞偋銈儓 ID 銇屾銇椼亸鍙栧緱銇曘倢銇︺亜銈嬨亱纰鸿獚銇椼仸銇忋仩銇曘亜

### 銉儑銉亴銉偣銉堛伀琛ㄧず銇曘倢銇亜
- OAuth 瑾嶈銇屾甯搞伀瀹屼簡銇椼仧銇撱仺銈掔⒑瑾嶃仐銇︺亸銇犮仌銇?- 瑾嶈銉椼儹銉曘偂銈ゃ儷銈广儓銉兗銈搞倰纰鸿獚锛歚~/.picoclaw/auth.json`
- `picoclaw auth login --provider antigravity` 銈掑啀瀹熻銇椼仸銇忋仩銇曘亜
