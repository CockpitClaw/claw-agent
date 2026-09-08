> Retour au [README](../project/README.fr.md)

# Guide d'authentification et d'int茅gration Antigravity

## Aper莽u

**Antigravity** (Google Cloud Code Assist) est un fournisseur de mod猫les IA soutenu par Google qui offre l'acc猫s 脿 des mod猫les tels que Claude Opus 4.6 et Gemini via l'infrastructure cloud de Google. Ce document fournit un guide complet sur le fonctionnement de l'authentification, la r茅cup茅ration des mod猫les et l'impl茅mentation d'un nouveau fournisseur dans PicoClaw.

---

## Table des mati猫res

1. [Flux d'authentification](#flux-dauthentification)
2. [D茅tails de l'impl茅mentation OAuth](#d茅tails-de-limpl茅mentation-oauth)
3. [Gestion des jetons](#gestion-des-jetons)
4. [R茅cup茅ration de la liste des mod猫les](#r茅cup茅ration-de-la-liste-des-mod猫les)
5. [Suivi de l'utilisation](#suivi-de-lutilisation)
6. [Structure du plugin fournisseur](#structure-du-plugin-fournisseur)
7. [Exigences d'int茅gration](#exigences-dint茅gration)
8. [Points de terminaison API](#points-de-terminaison-api)
9. [Configuration](#configuration)
10. [Cr茅er un nouveau fournisseur dans PicoClaw](#cr茅er-un-nouveau-fournisseur-dans-picoclaw)

---

## Flux d'authentification

### 1. OAuth 2.0 avec PKCE

Antigravity utilise **OAuth 2.0 avec PKCE (Proof Key for Code Exchange)** pour une authentification s茅curis茅e :

```
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                   鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?  Client    鈹?鈹€鈹€鈹€(1) Generate PKCE Pair鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€> 鈹?                鈹?鈹?            鈹?鈹€鈹€鈹€(2) Open Auth URL鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€> 鈹? Google OAuth   鈹?鈹?            鈹?                                   鈹?   Server       鈹?鈹?            鈹?<鈹€鈹€(3) Redirect with Code鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€ 鈹?                鈹?鈹?            鈹?                                   鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?            鈹?鈹€鈹€鈹€(4) Exchange Code for Tokens鈹€鈹€> 鈹?  Token URL     鈹?鈹?            鈹?                                   鈹?                鈹?鈹?            鈹?<鈹€鈹€(5) Access + Refresh Tokens鈹€鈹€鈹€鈹€ 鈹?                鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                   鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?```

### 2. 脡tapes d茅taill茅es

#### 脡tape 1 : G茅n茅rer les param猫tres PKCE
```typescript
function generatePkce(): { verifier: string; challenge: string } {
  const verifier = randomBytes(32).toString("hex");
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  return { verifier, challenge };
}
```

#### 脡tape 2 : Construire l'URL d'autorisation
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

**Port茅es requises :**
```typescript
const SCOPES = [
  "https://www.googleapis.com/auth/cloud-platform",
  "https://www.googleapis.com/auth/userinfo.email",
  "https://www.googleapis.com/auth/userinfo.profile",
  "https://www.googleapis.com/auth/cclog",
  "https://www.googleapis.com/auth/experimentsandconfigs",
];
```

#### 脡tape 3 : G茅rer le callback OAuth

**Mode automatique (d茅veloppement local) :**
- D茅marrer un serveur HTTP local sur le port 51121
- Attendre la redirection de Google
- Extraire le code d'autorisation des param猫tres de requ锚te

**Mode manuel (distant/sans interface graphique) :**
- Afficher l'URL d'autorisation 脿 l'utilisateur
- L'utilisateur compl猫te l'authentification dans son navigateur
- L'utilisateur colle l'URL de redirection compl猫te dans le terminal
- Analyser le code depuis l'URL coll茅e

#### 脡tape 4 : 脡changer le code contre des jetons
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

#### 脡tape 5 : R茅cup茅rer les donn茅es utilisateur suppl茅mentaires

**E-mail de l'utilisateur :**
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

**ID du projet (requis pour les appels API) :**
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
  return data.cloudaicompanionProject || "rising-fact-p41fc"; // Valeur par d茅faut
}
```

---

## D茅tails de l'impl茅mentation OAuth

### Identifiants client

**Important :** Ceux-ci sont encod茅s en base64 dans le code source pour la synchronisation avec pi-ai :

```typescript
const decode = (s: string) => Buffer.from(s, "base64").toString();

const CLIENT_ID = decode(
  "WU9VUl9HT09HTEVfQ0xJRU5UX0lE"
);
const CLIENT_SECRET = decode("WU9VUl9HT09HTEVfQ0xJRU5UX1NFQ1JFVA==");
```

### Modes de flux OAuth

1. **Flux automatique** (machines locales avec navigateur) :
   - Ouvre le navigateur automatiquement
   - Le serveur de callback local capture la redirection
   - Aucune interaction utilisateur requise apr猫s l'authentification initiale

2. **Flux manuel** (distant/sans interface/WSL2) :
   - URL affich茅e pour copier-coller manuellement
   - L'utilisateur compl猫te l'authentification dans un navigateur externe
   - L'utilisateur colle l'URL de redirection compl猫te

```typescript
function shouldUseManualOAuthFlow(isRemote: boolean): boolean {
  return isRemote || isWSL2Sync();
}
```

---

## Gestion des jetons

### Structure du profil d'authentification

```typescript
type OAuthCredential = {
  type: "oauth";
  provider: "google-antigravity";
  access: string;           // Jeton d'acc猫s
  refresh: string;          // Jeton de rafra卯chissement
  expires: number;          // Horodatage d'expiration (ms depuis epoch)
  email?: string;           // E-mail de l'utilisateur
  projectId?: string;       // ID du projet Google Cloud
};
```

### Rafra卯chissement des jetons

Les identifiants incluent un jeton de rafra卯chissement qui peut 锚tre utilis茅 pour obtenir de nouveaux jetons d'acc猫s lorsque le jeton actuel expire. L'expiration est d茅finie avec un tampon de 5 minutes pour 茅viter les conditions de concurrence.

---

## R茅cup茅ration de la liste des mod猫les

### R茅cup茅rer les mod猫les disponibles

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
  
  // Retourne les mod猫les avec les informations de quota
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

### Format de r茅ponse

```typescript
type FetchAvailableModelsResponse = {
  models?: Record<string, {
    displayName?: string;
    quotaInfo?: {
      remainingFraction?: number | string;
      resetTime?: string;      // Horodatage ISO 8601
      isExhausted?: boolean;
    };
  }>;
};
```

---

## Suivi de l'utilisation

### R茅cup茅rer les donn茅es d'utilisation

```typescript
export async function fetchAntigravityUsage(
  token: string,
  timeoutMs: number
): Promise<ProviderUsageSnapshot> {
  // 1. R茅cup茅rer les cr茅dits et les informations du plan
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

  // Extraire les informations de cr茅dits
  const { availablePromptCredits, planInfo, currentTier } = data;
  
  // 2. R茅cup茅rer les quotas des mod猫les
  const modelsRes = await fetch(
    `${BASE_URL}/v1internal:fetchAvailableModels`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: JSON.stringify({ project: projectId }),
    }
  );

  // Construire les fen锚tres d'utilisation
  return {
    provider: "google-antigravity",
    displayName: "Google Antigravity",
    windows: [
      { label: "Credits", usedPercent: calculateUsedPercent(available, monthly) },
      // Quotas individuels des mod猫les...
    ],
    plan: currentTier?.name || planType,
  };
}
```

### Structure de la r茅ponse d'utilisation

```typescript
type ProviderUsageSnapshot = {
  provider: "google-antigravity";
  displayName: string;
  windows: UsageWindow[];
  plan?: string;
  error?: string;
};

type UsageWindow = {
  label: string;           // "Credits" ou ID du mod猫le
  usedPercent: number;     // 0-100
  resetAt?: number;        // Horodatage de r茅initialisation du quota
};
```

---

## Structure du plugin fournisseur

### D茅finition du plugin

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
            // Impl茅mentation OAuth ici
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
  prompter: WizardPrompter;      // Invites/notifications UI
  runtime: RuntimeEnv;           // Journalisation, etc.
  isRemote: boolean;             // Ex茅cution 脿 distance ou non
  openUrl: (url: string) => Promise<void>;  // Ouverture du navigateur
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

## Exigences d'int茅gration

### 1. Environnement/d茅pendances requis

- Go 鈮?1.25
- Base de code PicoClaw (`pkg/providers/` et `pkg/auth/`)
- Packages de la biblioth猫que standard `crypto` et `net/http`

### 2. En-t锚tes requis pour les appels API

```typescript
const REQUIRED_HEADERS = {
  "Authorization": `Bearer ${accessToken}`,
  "Content-Type": "application/json",
  "User-Agent": "antigravity",  // ou "google-api-nodejs-client/9.15.1"
  "X-Goog-Api-Client": "google-cloud-sdk vscode_cloudshelleditor/0.1",
};

// Pour les appels loadCodeAssist, inclure 茅galement :
const CLIENT_METADATA = {
  ideType: "ANTIGRAVITY",  // ou "IDE_UNSPECIFIED"
  platform: "PLATFORM_UNSPECIFIED",
  pluginType: "GEMINI",
};
```

### 3. Assainissement des sch茅mas de mod猫les

Antigravity utilise des mod猫les compatibles Gemini, les sch茅mas d'outils doivent donc 锚tre assainis :

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

// Nettoyer le sch茅ma avant l'envoi
function cleanToolSchemaForGemini(schema: Record<string, unknown>): unknown {
  // Supprimer les mots-cl茅s non support茅s
  // S'assurer que le niveau sup茅rieur a type: "object"
  // Aplatir les unions anyOf/oneOf
}
```

### 4. Gestion des blocs de r茅flexion (mod猫les Claude)

Pour les mod猫les Claude via Antigravity, les blocs de r茅flexion n茅cessitent un traitement sp茅cial :

```typescript
const ANTIGRAVITY_SIGNATURE_RE = /^[A-Za-z0-9+/]+={0,2}$/;

export function sanitizeAntigravityThinkingBlocks(
  messages: AgentMessage[]
): AgentMessage[] {
  // Valider les signatures de r茅flexion
  // Normaliser les champs de signature
  // Rejeter les blocs de r茅flexion non sign茅s
}
```

---

## Points de terminaison API

### Points de terminaison d'authentification

| Point de terminaison | M茅thode | Objectif |
|---------------------|---------|----------|
| `https://accounts.google.com/o/oauth2/v2/auth` | GET | Autorisation OAuth |
| `https://oauth2.googleapis.com/token` | POST | 脡change de jetons |
| `https://www.googleapis.com/oauth2/v1/userinfo` | GET | Informations utilisateur (e-mail) |

### Points de terminaison Cloud Code Assist

| Point de terminaison | M茅thode | Objectif |
|---------------------|---------|----------|
| `https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist` | POST | Charger les infos du projet, cr茅dits, plan |
| `https://cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels` | POST | Lister les mod猫les disponibles avec quotas |
| `https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse` | POST | Point de terminaison de streaming de chat |

**Format de requ锚te API (chat) :**
Le point de terminaison `v1internal:streamGenerateContent` attend une enveloppe encapsulant la requ锚te Gemini standard :

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

**Format de r茅ponse API (SSE) :**
Chaque message SSE (`data: {...}`) est encapsul茅 dans un champ `response` :

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

## Configuration

### Configuration config.json

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

### Stockage du profil d'authentification

Les profils d'authentification sont stock茅s dans `~/.picoclaw/auth.json` :

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

## Cr茅er un nouveau fournisseur dans PicoClaw

Les fournisseurs PicoClaw sont impl茅ment茅s en tant que packages Go sous `pkg/providers/`. Pour ajouter un nouveau fournisseur :

### Impl茅mentation 茅tape par 茅tape

#### 1. Cr茅er le fichier du fournisseur

Cr茅ez un nouveau fichier Go dans `pkg/providers/` :

```
pkg/providers/
鈹斺攢鈹€ your_provider.go
```

#### 2. Impl茅menter l'interface Provider

Votre fournisseur doit impl茅menter l'interface `Provider` d茅finie dans `pkg/providers/types.go` :

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
    // Impl茅menter la compl茅tion de chat avec streaming
}
```

#### 3. Enregistrer dans la factory

Ajoutez votre fournisseur au switch de protocole dans `pkg/providers/factory.go` :

```go
case "your-provider":
    return NewYourProvider(sel.apiKey, sel.apiBase, sel.proxy), nil
```

#### 4. Ajouter la configuration par d茅faut (optionnel)

Ajoutez une entr茅e par d茅faut dans `pkg/config/defaults.go` :

```go
{
    ModelName: "your-model",
    Model:     "your-provider/model-name",
    APIKey:    "",
},
```

#### 5. Ajouter le support d'authentification (optionnel)

Si votre fournisseur n茅cessite OAuth ou une authentification sp茅ciale, ajoutez un cas dans `cmd/picoclaw/internal/auth/helpers.go` :

```go
case "your-provider":
    authLoginYourProvider()
```

#### 6. Configurer via `config.json`

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

## Tester votre impl茅mentation

### Commandes CLI

```bash
# S'authentifier avec un fournisseur
picoclaw auth login --provider your-provider

# Lister les mod猫les (pour Antigravity)
picoclaw auth models

# D茅marrer la passerelle
picoclaw gateway

# Ex茅cuter un agent avec un mod猫le sp茅cifique
picoclaw agent -m "Hello" --model your-model
```

### Variables d'environnement pour les tests

```bash
# Remplacer le mod猫le par d茅faut
export PICOCLAW_AGENTS_DEFAULTS_MODEL=your-model

# Remplacer les param猫tres du fournisseur
export PICOCLAW_MODEL_LIST='[{"model_name":"your-model","model":"your-provider/model-name","api_keys":["..."]}]'
```

---

## R茅f茅rences

- **Fichiers source :**
  - `pkg/providers/antigravity_provider.go` - Impl茅mentation du fournisseur Antigravity
  - `pkg/auth/oauth.go` - Impl茅mentation du flux OAuth
  - `pkg/auth/store.go` - Stockage des identifiants d'authentification (`~/.picoclaw/auth.json`)
  - `pkg/providers/factory.go` - Factory des fournisseurs et routage de protocole
  - `pkg/providers/types.go` - D茅finitions de l'interface fournisseur
  - `cmd/picoclaw/internal/auth/helpers.go` - Commandes CLI d'authentification

- **Documentation :**
  - `docs/ANTIGRAVITY_USAGE.md` - Guide d'utilisation d'Antigravity
  - `docs/migration/model-list-migration.md` - Guide de migration

---

## Notes

1. **Projet Google Cloud :** Antigravity n茅cessite que Gemini for Google Cloud soit activ茅 sur votre projet Google Cloud
2. **Quotas :** Utilise les quotas du projet Google Cloud (pas de facturation s茅par茅e)
3. **Acc猫s aux mod猫les :** Les mod猫les disponibles d茅pendent de la configuration de votre projet Google Cloud
4. **Blocs de r茅flexion :** Les mod猫les Claude via Antigravity n茅cessitent un traitement sp茅cial des blocs de r茅flexion avec signatures
5. **Assainissement des sch茅mas :** Les sch茅mas d'outils doivent 锚tre assainis pour supprimer les mots-cl茅s JSON Schema non support茅s

---

---

## Gestion des erreurs courantes

### 1. Limitation de d茅bit (HTTP 429)

Antigravity retourne une erreur 429 lorsque les quotas du projet/mod猫le sont 茅puis茅s. La r茅ponse d'erreur contient souvent un `quotaResetDelay` dans le champ `details`.

**Exemple d'erreur 429 :**
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

### 2. R茅ponses vides (mod猫les restreints)

Certains mod猫les peuvent appara卯tre dans la liste des mod猫les disponibles mais retourner une r茅ponse vide (200 OK mais flux SSE vide). Cela se produit g茅n茅ralement pour les mod猫les en pr茅version ou restreints que le projet actuel n'a pas la permission d'utiliser.

**Traitement :** Traiter les r茅ponses vides comme des erreurs informant l'utilisateur que le mod猫le pourrait 锚tre restreint ou invalide pour son projet.

---

## D茅pannage

### "Token expired" (jeton expir茅)
- Rafra卯chir les jetons OAuth : `picoclaw auth login --provider antigravity`

### "Gemini for Google Cloud is not enabled" (Gemini for Google Cloud n'est pas activ茅)
- Activer l'API dans votre Google Cloud Console

### "Project not found" (projet non trouv茅)
- V茅rifier que votre projet Google Cloud a les API n茅cessaires activ茅es
- V茅rifier que l'ID du projet est correctement r茅cup茅r茅 lors de l'authentification

### Les mod猫les n'apparaissent pas dans la liste
- V茅rifier que l'authentification OAuth s'est termin茅e avec succ猫s
- V茅rifier le stockage du profil d'authentification : `~/.picoclaw/auth.json`
- Relancer `picoclaw auth login --provider antigravity`
