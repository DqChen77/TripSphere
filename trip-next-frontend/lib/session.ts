import "server-only";
import { importSPKI, jwtVerify } from "jose";
import { cookies } from "next/headers";
import { SessionPayload } from "@/lib/definitions";

const COOKIE_NAME = "session";

// Lazily cache the public key so importSPKI runs only once
let _publicKey: CryptoKey | null = null;

async function getPublicKey(): Promise<CryptoKey> {
  if (!_publicKey) {
    _publicKey = await importSPKI(process.env.JWT_PUBLIC_KEY!, "RS256");
  }
  return _publicKey;
}

export async function verifyToken(
  token: string,
): Promise<SessionPayload | null> {
  try {
    const publicKey = await getPublicKey();
    const { payload } = await jwtVerify(token, publicKey, {
      algorithms: ["RS256"],
    });

    return {
      userId: payload.sub!,
      name: payload.name as string,
      email: payload.email as string,
      roles: payload.roles as string[],
      expiresAt: new Date(payload.exp! * 1000),
    };
  } catch {
    return null;
  }
}

export async function createSession(token: string): Promise<void> {
  const session = await verifyToken(token);
  if (!session) return;

  const cookieStore = await cookies();
  cookieStore.set(COOKIE_NAME, token, {
    httpOnly: true,
    secure: false,
    sameSite: "lax",
    path: "/",
    expires: session.expiresAt,
  });
}

export async function getToken(): Promise<string | null> {
  const cookieStore = await cookies();
  return cookieStore.get(COOKIE_NAME)?.value ?? null;
}

export async function getSession(): Promise<SessionPayload | null> {
  const token = await getToken();
  if (!token) return null;
  return verifyToken(token);
}

export async function deleteSession(): Promise<void> {
  const cookieStore = await cookies();
  cookieStore.delete(COOKIE_NAME);
}
