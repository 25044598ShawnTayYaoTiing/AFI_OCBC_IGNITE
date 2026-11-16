export interface Candidate {
  id: string; block: string; road: string; building: string; address: string;
  postalCode: string; latitude: number; longitude: number; source: 'ONEMAP_LIVE';
}
export interface AddressVerification {
  status: string; source: string; message: string; requestedAddress: string; postalCode: string;
  selected: Candidate | null; candidates: Candidate[]; checkedAt: string;
}
export interface SearchResult {
  status: string; message: string; postalCode: string; candidates: Candidate[]; complete: boolean; checkedAt: string;
}
export interface AddressRecord {
  address: string; postalCode: string; unit: string; candidateId?: string | null; source: string;
}
export interface AddressRequest {
  id: string; customerId: string; customerName?: string; customerReference?: string; addressType: string;
  previousAddress: AddressRecord | null; proposedAddress: AddressRecord; verification: AddressVerification;
  status: string; createdAt: string; reviewNote: string | null; reviewedBy: string | null;
}
export interface AddressBook {
  addresses: { addressType: string; address: AddressRecord; source: string; updatedAt: string }[];
  requests: AddressRequest[];
}
