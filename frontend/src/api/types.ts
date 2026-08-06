// Wire types mirroring the backend DTOs (com.nagad.deploy.dto.Dtos).

export interface ServiceCell {
  svc: string; host: string; status: string; hash: string; uptime: string;
  pid: string; ip: string; jar: string; instances: number; lastDeployed: string;
}
export interface GroupView {
  key: string; cmd: string | null; zone: string; tier: string;
  hosts: string[]; cells: ServiceCell[]; issues: string[];
}
export interface AttentionView {
  kind: string; title: string; sub: string; desc: string;
  group: string; svc: string; host: string;
}
export interface FleetView {
  collectedAt: string; age: string; incident: boolean;
  hostsTotal: number; servicesTotal: number; instancesTotal: number;
  servicesDown: number; driftGroups: number; restarts: number; unknowns: number;
  attention: AttentionView[]; groups: GroupView[];
}

export interface PromotionView {
  id: string; app: string; group: string; srcHost: string; jar: string;
  hash: string; prevHash: string | null; prodHash: string; deployed: boolean;
  branch: string | null; size: string | null;
  requestedBy: string; requestedAt: string; status: string;
  decidedBy: string | null; decidedAt: string | null; note: string | null;
}

export interface StagingSource {
  key: string; host: string; ip: string; apps: string[];
}

export interface DeployApp {
  key: string; jar: string; approved: boolean; approvedHash: string | null; prodHash: string;
}
export interface DeployGroup {
  key: string; cmd: string; zone: string; tier: string; hosts: string[]; apps: DeployApp[];
}

export interface StagedJarView {
  jar: string; app: string; hash: string; branch: string; commitDate: string;
  backup: boolean; prodHash: string; matchesProd: boolean;
}

export interface DeployPair { host: string; app: string; }

export interface PortalUiHost { host: string; ip: string; }
export interface PortalUiCatalog {
  uis: string[]; modes: string[]; prodHosts: PortalUiHost[]; staging: PortalUiHost;
}
export interface PortalUiRequest {
  mode: string; uis: string[]; hosts: string[]; fixUrl: boolean; fixSize: boolean; date: string;
}
export interface ConsolidatedPairView {
  host: string; group: string; app: string; jar: string;
  prodHash: string; targetHash: string | null; approved: boolean;
}

// ---- staging deployment (stg-deployment bundle, upload-driven) ----
export interface StgAppView { key: string; jar: string; host: string; ip: string; }
export interface StgGroupView { key: string; label: string; host: string; ip: string; apps: StgAppView[]; }
export interface StgCatalog { groups: StgGroupView[]; uis: string[]; workingDir: string; }
export interface StgUploadResponse { kind: string; target: string; storedName: string; targetPath: string; size: number; sha256: string; }
export interface StgDeployRequest { group: string; apps: string[]; actions: string[]; }
export interface StgPortalUiRequest { uis: string[]; date: string; urlFix: boolean; sizeFix: boolean; }

// ---- staging application-properties + history ----
export interface StgPropAppView { key: string; jar: string; cfgFile: string; }
export interface StgPropGroupView { key: string; label: string; host: string; ip: string; apps: StgPropAppView[]; }
export interface StgPropertiesCatalog { groups: StgPropGroupView[]; ops: string[]; blockOps: string[]; cfgPathTemplate: string; }
export interface StgPropertiesRequest {
  group: string; app: string; op: string; testMode: boolean;
  oldLine?: string; newLine?: string; key?: string; values?: string;
  oldKey?: string; newKey?: string; afterLine?: string; block?: string;
}
export interface StgPropTermLine { level: string; text: string; }
export interface StgPropertiesResult {
  command: string; host: string; targetFile: string; backupFile: string;
  testMode: boolean; changed: boolean; savedBlockPath: string | null; lines: StgPropTermLine[];
}
export interface StgHistoryRow {
  id: string; group: string; host: string; apps: string; actions: string;
  startedBy: string; startedAt: string; duration: string | null; result: string | null;
}
export interface StgServiceHash { app: string; jar: string; host: string; hash: string; status: string; }

export interface DeploymentView {
  id: string; promotionId: string | null; group: string; hosts: string; apps: string;
  actions: string; startedBy: string; startedAt: string; duration: string | null;
  result: string | null; beforeAfter: string | null; logExcerpt: string | null;
}

export interface RegistryRow {
  app: string; group: string; jar: string; prodHash: string | null;
  latestHash: string | null; latestStatus: string; latestBy: string | null; updatedAt: string;
}

export interface AdminRow {
  username: string; name: string; role: string; scope: string; r: boolean; w: boolean; x: boolean;
}

export interface AuditRow {
  ts: string; actor: string; verb: string; target: string; detail: string | null;
}

// ---- application-properties (surgical in-place editing of application.properties) ----
export interface PropAppView { key: string; jar: string; cfgFile: string; }
export interface PropGroupView {
  key: string; cmd: string; zone: string; tier: string; hosts: string[]; apps: PropAppView[];
}
export interface PropertiesCatalog {
  groups: PropGroupView[]; ops: string[]; blockOps: string[]; cfgPathTemplate: string;
}
export interface PropertiesRequest {
  hosts: string[]; app: string; op: string; testMode: boolean;
  oldLine?: string; newLine?: string; key?: string; values?: string;
  oldKey?: string; newKey?: string; afterLine?: string; block?: string;
}
export interface PropTermLine { level: string; text: string; }
export interface PropertiesResult {
  command: string; targetFile: string; backupFile: string;
  testMode: boolean; changed: boolean; savedBlockPath: string | null; lines: PropTermLine[];
}
