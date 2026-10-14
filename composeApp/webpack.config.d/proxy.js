// RFI e Trenord non mandano intestazioni CORS: in sviluppo il dev server inoltra /rfi/* e
// /trenord/* e aggiunge lo User-Agent di un browser desktop, che dal browser non si può impostare.
const userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:157.0) Gecko/20100101 Firefox/157.0";

if (config.devServer) {
    config.devServer.proxy = [
        { prefix: "/rfi", target: "https://iechub.rfi.it" },
        { prefix: "/trenord", target: "https://www.trenord.it" },
    ].map(({ prefix, target }) => ({
        context: [prefix],
        target,
        changeOrigin: true,
        secure: true,
        pathRewrite: { [`^${prefix}`]: "" },
        headers: { "User-Agent": userAgent },
    }));
}
