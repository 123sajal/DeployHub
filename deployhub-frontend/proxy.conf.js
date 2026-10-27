// Dev-server proxy: `ng serve` forwards these paths to the backend.
// Defaults to http://localhost:8080; override with API_PORT, e.g. `API_PORT=8081 npm start`.
const target = `http://localhost:${process.env.API_PORT || 8080}`;

module.exports = {
  '/api': { target, secure: false },
  '/swagger-ui': { target, secure: false },
  '/v3/api-docs': { target, secure: false },
};
