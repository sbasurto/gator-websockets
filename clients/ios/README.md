# Cliente iOS

Swift Package para iOS 13 o posterior. Usa `URLSessionWebSocketTask` y
CryptoKit; no requiere dependencias externas.

```swift
let client = GatorWebSocketClient(url: URL(string: "wss://example.com:8080")!)
client.onMessage = { print($0) }
client.onError = { print($0) }
client.onState = { state in
    if state == "authenticated" {
        client.send(["type": "getuserlist"])
    }
}

client.connect(usuario: "usuario-id", passphrase: "passphrase")
```

En macOS con Xcode instalado:

```bash
swift test
```
