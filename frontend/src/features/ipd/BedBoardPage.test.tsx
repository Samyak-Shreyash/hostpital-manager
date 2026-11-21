import { screen } from '@testing-library/react'
import { json, mockApi, renderApp, session } from '../../test/helpers'
import { AVAILABILITY } from './fixtures'

describe('bed board', () => {
  it('shows occupied, available and out-of-service counts and links occupied beds to the admission', async () => {
    mockApi({
      'GET /api/v1/auth/me': json(200, session(['bed:read', 'ward:read', 'admission:read', 'admission:create'])),
      'GET /api/v1/wards?size=100': json(200, { items: [], page: 0, size: 100, totalItems: 0 }),
      'GET /api/v1/beds/availability?': json(200, AVAILABILITY),
    })
    renderApp('/beds')
    expect(await screen.findByTestId('occupied-GEN')).toHaveTextContent('Occupied: 1')
    expect(screen.getByTestId('available-GEN')).toHaveTextContent('Available: 2')
    expect(screen.getByTestId('out-GEN')).toHaveTextContent('Out of service: 1')
    expect(screen.getByRole('link', { name: 'Open admission for bed G-1' })).toHaveAttribute('href', '/admissions/adm-1')
    expect(screen.getAllByRole('link', { name: 'Admit patient' }).length).toBeGreaterThan(0)
  })

  it('is forbidden without bed:read and hides the nav entry', async () => {
    mockApi({ 'GET /api/v1/auth/me': json(200, session(['patient:search'])) })
    renderApp('/beds')
    expect(await screen.findByText('Bed board is not available to you')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Bed board' })).not.toBeInTheDocument()
  })
})
