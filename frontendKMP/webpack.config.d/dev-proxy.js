// Development-only webpack dev-server proxy: keeps the app on same-origin
// /api/* in BOTH modes without any frontend code change.
//   prod: nginx proxies /api -> backend:8081 (compose network)
//   dev : the dev server runs on the HOST (WSL2-backed, fast); the backend is
//         host-published on :8081 via compose, so this proxy targets
//         http://localhost:8081.
if (config.mode === 'development') {
    config.devServer = config.devServer || {};
    config.devServer.proxy = [
        { context: ['/api'], target: 'http://localhost:8081' }
    ];
}