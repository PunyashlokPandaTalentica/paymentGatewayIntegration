import http.server
import ssl

server_address = ('localhost', 8443)
httpd = http.server.HTTPServer(server_address, http.server.SimpleHTTPRequestHandler)

httpd.socket = ssl.wrap_socket(
    httpd.socket,
    certfile='localhost.pem',
    keyfile='localhost-key.pem',
    server_side=True
)

print("Serving HTTPS on https://localhost:8443")
httpd.serve_forever()

