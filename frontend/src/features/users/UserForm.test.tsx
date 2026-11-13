import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { UserForm } from './UserForm'

const roles = [
  { code: 'DOCTOR', name: 'Doctor' },
  { code: 'NURSE', name: 'Nurse' },
]

describe('UserForm', () => {
  it('requires email, name and a role', async () => {
    const onSubmit = vi.fn()
    render(<UserForm mode="create" roleOptions={roles} onSubmit={onSubmit} />)
    fireEvent.click(screen.getByRole('button', { name: 'Create user' }))
    expect(await screen.findByText('Email is required')).toBeInTheDocument()
    expect(screen.getByText('Full name is required')).toBeInTheDocument()
    expect(screen.getByText('Select at least one role')).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('rejects a malformed email', async () => {
    render(<UserForm mode="create" roleOptions={roles} onSubmit={vi.fn()} />)
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'nope' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create user' }))
    expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument()
  })

  it('submits trimmed values with the selected role codes', async () => {
    const onSubmit = vi.fn()
    render(<UserForm mode="create" roleOptions={roles} onSubmit={onSubmit} />)
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
    render(<UserForm mode="create" roleOptions={roles} emailError="This email address is already in use." onSubmit={vi.fn()} />)
    expect(screen.getByText('This email address is already in use.')).toBeInTheDocument()
  })

  it('makes the email read-only when editing and pre-selects roles', () => {
    render(
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
