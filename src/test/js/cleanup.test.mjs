import { closeDashboards } from './support/dashboard-dom.mjs';

// A dashboard started in a test may poll on a real timer. Closing its window after each test
// stops the timer, so the test run can exit.
afterEach(() => closeDashboards());
