import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router'
import { UserForm } from './UserForm'

const roles = [
  { code: 'DOCTOR', name: 'Doctor' },
  { code: 'NURSE', name: 'Nurse' },
]

// The form links back to the user list, so it needs a router.
const renderForm = (ui: ReactElement) => render(<MemoryRouter>{ui}</MemoryRouter>)

describe('UserForm', () => {
  it('shows progress while submitting and offers a way back', () => {
    renderForm(<UserForm mode="create" roleOptions={roles} submitting onSubmit={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Creating…' })).toBeDisabled()
    expect(screen.getByRole('link', { name: 'Cancel' })).toHaveAttribute('href', '/users')
  })

  it('requires email, name and a role', async () => {
    const onSubmit = vi.fn()
    renderForm(<UserForm mode="create" roleOptions={roles} onSubmit={onSubmit} />)
    fireEvent.click(screen.getByRole('button', { name: 'Create user' }))
    expect(await screen.findByText('Email is required')).toBeInTheDocument()
    expect(screen.getByText('Full name is required')).toBeInTheDocument()
    expect(screen.getByText('Select at least one role')).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('rejects a malformed email', async () => {
    renderForm(<UserForm mode="create" roleOptions={roles} onSubmit={vi.fn()} />)
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'nope' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create user' }))
    expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument()
  })

  it('submits trimmed values with the selected role codes', async () => {
    const onSubmit = vi.fn()
    renderForm(<UserForm mode="create" roleOptions={roles} onSubmit={onSubmit} />)
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'dr.rao@example.test' } })
    fireEvent.change(screen.getByLabelText('Full name'), { target: { value: '  Dr. A. Rao ' } })
    fireEvent.click(screen.getByLabelText(/Doctor/))
    fireEvent.click(screen.getByRole('button', { name: 'Create user' }))
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][0]).toEqual({
      email: 'dr.rao@example.test',
      fullName: 'Dr. A. Rao',
      roleCodes: ['DOCTOR'],
    })
  })

  it('shows a duplicate-email message under the email field', () => {
    renderForm(<UserForm mode="create" roleOptions={roles} emailError="This email address is already in use." onSubmit={vi.fn()} />)
    expect(screen.getByText('This email address is already in use.')).toBeInTheDocument()
  })

  it('makes the email read-only when editing and pre-selects roles', () => {
    renderForm(
      <UserForm
        mode="edit"
        roleOptions={roles}
        initial={{ email: 'a@example.test', fullName: 'A', roleCodes: ['NURSE'] }}
        onSubmit={vi.fn()}
      />,
    )
    expect(screen.getByLabelText('Email')).toHaveAttribute('readonly')
    expect(screen.getByLabelText(/Nurse/)).toBeChecked()
    expect(screen.getByLabelText(/Doctor/)).not.toBeChecked()
  })
})
