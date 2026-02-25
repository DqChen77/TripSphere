import "server-only";
import { credentials, Metadata, type ChannelCredentials } from "@grpc/grpc-js";
import { headers } from "next/headers";
import { UserServiceClient } from "./generated/tripsphere/user/v1/user";
import { HotelServiceClient } from "./generated/tripsphere/hotel/v1/hotel";
import { AttractionServiceClient } from "./generated/tripsphere/attraction/v1/attraction";
import { ItineraryServiceClient } from "./generated/tripsphere/itinerary/v1/itinerary";
import { PoiServiceClient } from "./generated/tripsphere/poi/v1/poi";

// Static service addresses
const SERVICE_ADDRESSES = {
  attraction: "localhost:50053",
  hotel: "localhost:50054",
  itinerary: "localhost:50052",
  poi: "localhost:50058",
  user: "localhost:50056",
};

const clientCache = new Map<string, unknown>();

function getClient<T>(
  ClientConstructor: new (
    address: string,
    credentials: ChannelCredentials,
  ) => T,
  address: string,
): T {
  const key = `${ClientConstructor.name}@${address}`;
  if (!clientCache.has(key)) {
    clientCache.set(
      key,
      new ClientConstructor(address, credentials.createInsecure()),
    );
  }
  return clientCache.get(key) as T;
}

export function getUserService() {
  return getClient(UserServiceClient, SERVICE_ADDRESSES.user);
}

export function getHotelService() {
  return getClient(HotelServiceClient, SERVICE_ADDRESSES.hotel);
}

export function getAttractionService() {
  return getClient(AttractionServiceClient, SERVICE_ADDRESSES.attraction);
}

export function getItineraryService() {
  return getClient(ItineraryServiceClient, SERVICE_ADDRESSES.itinerary);
}

export function getPoiService() {
  return getClient(PoiServiceClient, SERVICE_ADDRESSES.poi);
}

export async function getAuthMetadata(): Promise<Metadata> {
  const metadata = new Metadata();
  const reqHeaders = await headers();
  const authorization = reqHeaders.get("authorization");
  const userId = reqHeaders.get("x-user-id");
  const userRoles = reqHeaders.get("x-user-roles");
  if (authorization) metadata.set("authorization", authorization);
  if (userId) metadata.set("x-user-id", userId);
  if (userRoles) metadata.set("x-user-roles", userRoles);
  return metadata;
}
