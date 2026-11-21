import type { Admission, WardAvailability } from './api'

// Synthetic data only.
export const GENERAL_WARD = { id: 'w-1', name: 'General Ward', careType: 'GENERAL' as const }
export const AVAILABILITY: { wards: WardAvailability[] } = {
  wards: [{
    ward: GENERAL_WARD, code: 'GEN', status: 'ACTIVE', total: 4, occupied: 1, available: 2, outOfService: 1,
    beds: [
      { id: 'b-1', bedNo: 'G-1', bedClass: null, ward: GENERAL_WARD, status: 'ACTIVE', occupied: true, admissionId: 'adm-1' },
      { id: 'b-2', bedNo: 'G-2', bedClass: null, ward: GENERAL_WARD, status: 'ACTIVE', occupied: false, admissionId: null },
      { id: 'b-3', bedNo: 'G-3', bedClass: null, ward: GENERAL_WARD, status: 'ACTIVE', occupied: false, admissionId: null },
      { id: 'b-4', bedNo: 'G-4', bedClass: null, ward: GENERAL_WARD, status: 'OUT_OF_SERVICE', occupied: false, admissionId: null },
    ],
  }],
}

export const ADMISSION: Admission = {
  id: 'adm-1', admissionNo: 'ADM-2026-0001', patientId: 'p-1', patient: { id: 'p-1', uhid: 'DEMO-000001', name: 'Asha Testpatient' },
  admittingDoctorId: 'd-1', sourceEncounterId: null, admissionDiagnosis: 'Acute gastroenteritis', status: 'ADMITTED',
  admittedAt: '2026-11-20T04:00:00Z', plannedDischargeAt: null, dischargedAt: null,
  currentBed: { id: 'b-1', bedNo: 'G-1', ward: 'General Ward' }, version: 3,
}
