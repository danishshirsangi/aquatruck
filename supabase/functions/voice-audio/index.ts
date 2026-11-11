import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.1";
import { createRemoteJWKSet, jwtVerify } from "https://esm.sh/jose@5.9.6";

const FIREBASE_PROJECT_ID = "gen-lang-client-0614708651";
const FIRESTORE_DATABASE_ID = "ai-studio-android-aquatrac-ae0f6f83-854d-4712-bbed-b747ec9132c0";
const BUCKET = "aquatruck-audio";
const MAX_BYTES = 20 * 1024 * 1024;
const jwks = createRemoteJWKSet(
  new URL("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"),
);

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

function field(doc: Record<string, unknown>, name: string): unknown {
  const fields = doc.fields as Record<string, Record<string, unknown>> | undefined;
  const value = fields?.[name];
  if (!value) return undefined;
  return value.stringValue ?? value.integerValue ?? value.booleanValue;
}

async function verifyFirebaseToken(request: Request) {
  const authorization = request.headers.get("Authorization") ?? "";
  const token = authorization.startsWith("Bearer ") ? authorization.slice(7) : "";
  if (!token) throw new Error("Missing Firebase bearer token");
  const { payload } = await jwtVerify(token, jwks, {
    issuer: `https://securetoken.google.com/${FIREBASE_PROJECT_ID}`,
    audience: FIREBASE_PROJECT_ID,
  });
  if (!payload.sub || typeof payload.sub !== "string") throw new Error("Invalid Firebase user");
  return { uid: payload.sub, token };
}

async function getMembership(orgId: string, uid: string, token: string) {
  const path = [
    "organizations", encodeURIComponent(orgId), "members", encodeURIComponent(uid),
  ].join("/");
  const url = `https://firestore.googleapis.com/v1/projects/${FIREBASE_PROJECT_ID}/databases/${FIRESTORE_DATABASE_ID}/documents/${path}`;
  const response = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  if (!response.ok) throw new Error("Unable to verify AquaTruck business membership");
  const document = await response.json();
  const role = field(document, "role");
  const status = field(document, "status");
  const assignedTruckId = Number(field(document, "assignedTruckId") ?? 0);
  if (status !== "active" || !["manager", "driver"].includes(String(role))) {
    throw new Error("Active business membership required");
  }
  return { role: String(role), assignedTruckId };
}

function safePart(value: string) {
  return /^[a-zA-Z0-9_-]{1,100}$/.test(value);
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return json({ error: "Method not allowed" }, 405);

  try {
    const declaredLength = Number(request.headers.get("content-length") ?? "0");
    if (Number.isFinite(declaredLength) && declaredLength > MAX_BYTES + 128 * 1024) {
      return json({ error: "Audio request exceeds the 20 MB limit" }, 413);
    }
    const { uid, token } = await verifyFirebaseToken(request);
    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!supabaseUrl || !serviceRoleKey) throw new Error("Storage service is not configured");
    const storage = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    const contentType = request.headers.get("content-type") ?? "";
    let action: string;
    let orgId: string;
    let truckId: number;
    let timestamp: number;
    let file: File | null = null;
    let extension: string;

    if (contentType.includes("multipart/form-data")) {
      const form = await request.formData();
      action = "upload";
      orgId = String(form.get("orgId") ?? "");
      truckId = Number(form.get("truckId"));
      timestamp = Number(form.get("timestamp"));
      file = form.get("file") instanceof File ? form.get("file") as File : null;
      extension = String(form.get("extension") ?? "m4a").toLowerCase();
    } else {
      const body = await request.json();
      action = String(body.action ?? "");
      orgId = String(body.orgId ?? "");
      truckId = Number(body.truckId);
      timestamp = Number(body.timestamp);
      extension = String(body.extension ?? "m4a").toLowerCase();
    }

    if (!safePart(orgId) || !Number.isSafeInteger(truckId) || truckId <= 0 ||
        !Number.isSafeInteger(timestamp) || timestamp <= 0 ||
        timestamp > Date.now() + 5 * 60 * 1000 ||
        !["m4a", "mp4", "wav"].includes(extension)) {
      return json({ error: "Invalid audio reference" }, 400);
    }

    const membership = await getMembership(orgId, uid, token);
    if (membership.role === "driver" && membership.assignedTruckId !== truckId) {
      return json({ error: "This recording does not belong to your assigned truck" }, 403);
    }

    const objectPath = `organizations/${orgId}/voice/voice_${truckId}_${timestamp}.${extension}`;

    if (action === "upload") {
      if (!file || file.size <= 0 || file.size > MAX_BYTES) {
        return json({ error: "Audio must be between 1 byte and 20 MB" }, 400);
      }
      const expectedType = extension === "wav" ? "audio/wav" : "audio/mp4";
      const allowedTypes = extension === "wav"
        ? ["audio/wav", "audio/x-wav"]
        : ["audio/mp4", "audio/x-m4a", "audio/m4a"];
      if (file.type && !allowedTypes.includes(file.type.toLowerCase())) {
        return json({ error: "Audio content type does not match the selected format" }, 400);
      }
      const { error } = await storage.storage.from(BUCKET).upload(objectPath, file, {
        contentType: expectedType,
        upsert: true,
      });
      if (error) throw error;
      return json({ objectPath });
    }

    if (action === "download") {
      const { data, error } = await storage.storage.from(BUCKET).download(objectPath);
      if (error || !data) return json({ error: "Recording not found" }, 404);
      return new Response(data, {
        status: 200,
        headers: {
          ...corsHeaders,
          "Content-Type": extension === "wav" ? "audio/wav" : "audio/mp4",
          "Content-Length": String(data.size),
          "Cache-Control": "private, no-store",
        },
      });
    }

    return json({ error: "Unsupported action" }, 400);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Request failed";
    const status = /token|membership|business|assigned truck/i.test(message) ? 401 : 500;
    return json({ error: message }, status);
  }
});
