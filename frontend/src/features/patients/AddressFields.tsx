import type { FieldErrors, FieldValues, Path, UseFormRegister } from 'react-hook-form'
import { Field, inputClass } from '../../components/ui'

type AddressShape = { line1: string; line2: string; city: string; state: string; pincode: string }

/** Optional address block shared by registration and edit forms. */
export function AddressFields<T extends FieldValues & AddressShape>({ register, errors }: { register: UseFormRegister<T>; errors: FieldErrors<T> }) {
  const err = (name: keyof AddressShape) => (errors[name]?.message as string | undefined)
  const reg = (name: keyof AddressShape) => register(name as Path<T>)
  return (
    <fieldset className="mb-4">
      <legend className="mb-2 text-sm font-medium">Address (optional)</legend>
      <Field label="Address line 1" htmlFor="line1" error={err('line1')}>
        <input id="line1" className={inputClass} autoComplete="off" {...reg('line1')} />
      </Field>
      <Field label="Address line 2" htmlFor="line2" error={err('line2')}>
        <input id="line2" className={inputClass} autoComplete="off" {...reg('line2')} />
      </Field>
      <div className="grid grid-cols-3 gap-3">
        <Field label="City" htmlFor="city" error={err('city')}>
          <input id="city" className={inputClass} autoComplete="off" {...reg('city')} />
        </Field>
        <Field label="State" htmlFor="state" error={err('state')}>
          <input id="state" className={inputClass} autoComplete="off" {...reg('state')} />
        </Field>
        <Field label="Pincode" htmlFor="pincode" error={err('pincode')}>
          <input id="pincode" inputMode="numeric" className={inputClass} autoComplete="off" {...reg('pincode')} />
        </Field>
      </div>
    </fieldset>
  )
}
