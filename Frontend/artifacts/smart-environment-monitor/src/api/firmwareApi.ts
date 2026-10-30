import { httpClient, absentAsNull, type ApiResponse } from './httpClient';

/** Mirrors device-service `FirmwareResponse`. */
export interface FirmwareRelease {
  id?: number;
  version: string;
  fileUrl?: string;
  checksum?: string;
  fileSize?: number;
  releaseNotes?: string;
  createdAt?: string;
}

export interface FirmwareUpdateRecord {
  id: number;
  deviceUid: string;
  firmwareVersion: string;
  status: string;
  requestedAt: string;
}

export const firmwareApi = {
  /**
   * Does not catch: "no releases registered" is an operational fact, while
   * "the catalogue could not be read" is an outage.
   */
  list: async (): Promise<FirmwareRelease[]> => {
    const { data } = await httpClient.get<ApiResponse<FirmwareRelease[]>>('/firmware');
    return data.data ?? [];
  },

  /** Null only on a 404: that version is not in the catalogue. */
  getByVersion: async (version: string): Promise<FirmwareRelease | null> => {
    try {
      const { data } = await httpClient.get<ApiResponse<FirmwareRelease>>(`/firmware/${version}`);
      return data.data ?? null;
    } catch (error) {
      return absentAsNull(error);
    }
  },

  register: async (release: { version: string; fileUrl: string; checksum: string; fileSize: number; releaseNotes?: string }) => {
    const { data } = await httpClient.post<ApiResponse<FirmwareRelease>>('/firmware', release);
    return data.data;
  },

  /**
   * Requests an OTA push to one device. This only records the request and
   * forwards it toward AWS IoT Core — the caller must confirm with the
   * operator first, since a firmware push is disruptive and not reversible
   * from the console.
   */
  requestUpdate: async (deviceUid: string, firmwareVersion: string): Promise<ApiResponse<DeviceFirmwareUpdateResponse>> => {
    const { data } = await httpClient.post<ApiResponse<DeviceFirmwareUpdateResponse>>(
      `/devices/${deviceUid}/firmware-update`,
      { firmwareVersion },
    );
    return data;
  },
};

export interface DeviceFirmwareUpdateResponse {
  id: number;
  deviceUid: string;
  firmwareVersion: string;
  status: string;
}
