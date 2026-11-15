# Built-in roles and permissions

What each built-in role can do, with every wildcard expanded. Derived from code (2026-11-16):
`DefaultRoleTemplates.java` (role → permissions) and `Permissions.java` (the catalogue). If this file and the code
disagree, the code wins; update this file in the same PR as any role-template change.

Built-in roles are copied into each organization at bootstrap. A System Administrator can also create custom roles
(`role:manage`) and assign roles to users (`role:assign`). Custom roles are not listed here.

## Who gets which role

| Role (code) | Typical user | Job |
|---|---|---|
| System Administrator (`SYSTEM_ADMIN`) | IT / owner's admin | Users, roles, organization settings. No clinical or financial access. |
| Facility Administrator (`FACILITY_ADMIN`) | Hospital/clinic manager | Departments, wards, beds, service catalogue, doctors, staff, schedules. |
| Doctor (`DOCTOR`) | Consultants, medical officers | Consultations, prescriptions, admissions, discharge. |
| Nurse (`NURSE`) | Ward / OPD nurses | Vitals and observations, allergies/history, bed assignment. |
| Receptionist (`RECEPTIONIST`) | Front desk | Patient registration, appointments, admission intake, collecting payments. |
| Accountant (`ACCOUNTANT`) | Billing / accounts | Invoices, payments, refunds, finance reports. |

## Permissions per role

### System Administrator — 10
| Area | Permissions |
|---|---|
| Users | `user:create`, `user:read`, `user:update`, `user:reset-password` |
| Roles | `role:read`, `role:manage`, `role:assign` |
| Organization | `org:read`, `org:update` |
| Audit | `audit:read` |

### Facility Administrator — 17
| Area | Permissions |
|---|---|
| Users | `user:read` (to link staff/doctors to accounts; cannot create users or assign roles) |
| Service catalogue | `catalog:read`, `catalog:manage` |
| Wards & beds | `ward:read`, `ward:manage`, `bed:read`, `bed:manage`, `bed:assign` |
| Doctors | `practitioner:create`, `practitioner:read`, `practitioner:update`, `practitioner:schedule` |
| Staff | `staff:create`, `staff:read`, `staff:update` |
| Audit & reports | `audit:read`, `report:clinical` |

### Doctor — 21
| Area | Permissions |
|---|---|
| Patients | `patient:read`, `patient:update-clinical` |
| Encounters | `encounter:create`, `encounter:read`, `encounter:update`, `encounter:update-any`, `encounter:finalize`, `encounter:addendum` |
| Prescriptions | `prescription:create`, `prescription:read`, `prescription:issue` |
| Admissions | `admission:create`, `admission:read`, `admission:update`, `admission:cancel` |
| Discharge | `discharge:write`, `discharge:complete`, `discharge:defer-billing` |

### Nurse — 5
| Area | Permissions |
|---|---|
| Patients | `patient:read`, `patient:update-clinical` |
| Observations | `observation:write` |
| Beds & admissions | `bed:assign`, `admission:read` |

### Receptionist — 14
| Area | Permissions |
|---|---|
| Patients | `patient:read`, `patient:search`, `patient:create`, `patient:update`, `patient:override-duplicate` |
| Appointments | `appointment:create`, `appointment:read`, `appointment:update`, `appointment:cancel`, `appointment:check-in`, `appointment:backdate`, `appointment:override` |
| Admissions | `admission:create` |
| Payments | `payment:create` |

### Accountant — 12
| Area | Permissions |
|---|---|
| Invoices | `invoice:create`, `invoice:read`, `invoice:update`, `invoice:issue`, `invoice:credit`, `invoice:price-override` |
| Payments | `payment:create`, `payment:read` |
| Refunds | `refund:create`, `refund:read`, `refund:approve` |
| Reports | `report:finance` |

## Open gaps (need an owner decision; not yet changed)

1. ~~Linking staff/doctors to user accounts~~: resolved 2026-11-16, Facility Administrator has `user:read` (V21).
2. **`patient:update-identity`** is held by no built-in role (removed from Receptionist 2026-11-14), so identity
   fields (name, DOB, ABHA…) can only be corrected via a custom role.
3. **Doctor and Nurse have no `patient:search`** and Doctor has no `appointment:read`. Check whether they can find patients and see their
   OPD queue once those screens exist.
4. **Accountant holds both `refund:create` and `refund:approve`.** Decide whether refunds need a second approver.
