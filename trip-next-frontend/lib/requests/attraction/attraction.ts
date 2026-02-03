import type { Attraction as GrpcAttraction } from "@/lib/grpc/gen/tripsphere/attraction/v1/attraction";
import type { GeoPoint } from "@/lib/grpc/gen/tripsphere/common/v1/map";
import { get, post } from "@/lib/requests/base/request";

/**
 * Request type for searching attractions within a radius
 */
export interface FindAttractionsWithinRadiusRequest {
  location: GeoPoint;
  radius_km: number;
  tags?: string[];
}

/**
 * Find an attraction by its ID
 * @param id - The attraction ID
 * @returns The attraction details
 */
export async function findAttractionById(id: string) {
  return get<GrpcAttraction>(`/api/v1/attractions/${id}`);
}

/**
 * Search for attractions within a specified radius from a location
 * @param request - Search parameters including location, radius, and optional filters
 * @returns List of attractions matching the criteria
 */
export async function findAttractionsWithinRadius(
  request: FindAttractionsWithinRadiusRequest,
) {
  return post<GrpcAttraction[]>("/api/v1/attractions/nearby", request);
}
