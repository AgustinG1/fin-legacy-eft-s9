using System;
using System.IO;
using System.Net.Http;
using System.Net.Security;

public static class LocalTls
{
    public static HttpClient CreateClient(string directory)
    {
        var handler = new HttpClientHandler();
        handler.AllowAutoRedirect = false;
        handler.ServerCertificateCustomValidationCallback = (request, certificate, chain, errors) =>
        {
            string service;
            switch (request.RequestUri.Port)
            {
                case 8201: service = "bff-web"; break;
                case 8202: service = "bff-movil"; break;
                case 8203: service = "bff-cajero"; break;
                default: return errors == SslPolicyErrors.None;
            }
            if (request.RequestUri.Host != "localhost" || certificate == null ||
                (errors & ~SslPolicyErrors.RemoteCertificateChainErrors) != SslPolicyErrors.None)
                return false;
            var file = Path.Combine(directory, service + ".cer");
            if (!File.Exists(file)) return false;
            return Convert.ToBase64String(certificate.RawData) == Convert.ToBase64String(File.ReadAllBytes(file))
                    && DateTime.UtcNow >= certificate.NotBefore.ToUniversalTime()
                    && DateTime.UtcNow <= certificate.NotAfter.ToUniversalTime();
        };
        return new HttpClient(handler);
    }
}
