# Cliente iOS

Cliente oficial para iOS 13 o posterior, distribuido como Swift Package. Usa
`URLSessionWebSocketTask` y CryptoKit, sin dependencias externas. Implementa la
autenticación HPKE y el cifrado bidireccional del protocolo Gator.

## Instalación

En Xcode selecciona **File → Add Package Dependencies** y agrega:

```text
https://github.com/sbasurto/gator-websockets.git
```

Selecciona el producto `GatorWebSockets`. El repositorio incluye un manifiesto
SwiftPM en la raíz para integración remota y otro dentro de `clients/ios` para
desarrollo aislado del cliente.

## Uso

```swift
import GatorWebSockets

let client = GatorWebSocketClient(
    url: URL(string: "wss://example.com:8080")!,
    keepAliveInterval: 60
)

client.onState = { state in
    print("WebSocket:", state)
    if state == "authenticated" {
        client.subscribe(["screen/orders"])
        client.publish(
            kind: "topic",
            ids: ["screen/orders"],
            payload: ["type": "order.updated", "data": ["orderId": "123"]]
        ) { result in
            if case .failure(let error) = result {
                print("No se pudo enviar:", error)
            }
        }
    }
}

client.onMessage = { message in
    print("Mensaje:", message)
}

client.onEvent = { event in
    print("Evento:", event)
}

client.onError = { error in
    print("Error:", error.localizedDescription)
}

client.connect(accessToken: accessToken)
```

También expone `unsubscribe`, `presence` y `ack`. El cliente deduplica y
confirma automáticamente los mensajes v2 válidos.

Los callbacks se entregan en la cola principal de manera predeterminada. Puede
proporcionarse otra cola mediante `callbackQueue`; configura los callbacks antes
de llamar a `connect`. Los estados emitidos son:

```text
connecting → connected → authenticating → authenticated → closed
```

`authenticated` y `state` pueden consultarse desde cualquier hilo. `send`
serializa los mensajes para conservar la secuencia criptográfica. Un fallo de
envío cierra la sesión porque la secuencia ya no puede reutilizarse.

## Keepalive y cierre

El cliente envía un ping cada 60 segundos de forma predeterminada. Esto mantiene
la sesión activa frente a `idleTimeoutSeconds` del servidor. Usa cero para
deshabilitarlo:

```swift
let client = GatorWebSocketClient(url: url, keepAliveInterval: 0)
```

Para cerrar:

```swift
client.close()
client.close(code: .goingAway, reason: "La aplicación pasa a segundo plano")
```

La razón se limita a 123 bytes UTF-8, como exige RFC 6455.

## Seguridad

Utiliza `wss://` en producción. TLS autentica la oferta de clave HPKE; con
`ws://`, un intermediario podría sustituirla. El cliente valida la suite,
identificador de clave, secuencias y tags AES-GCM y cierra la conexión ante
cualquier envelope inválido.

## Pruebas

Con Xcode y sus Command Line Tools alineados:

```bash
swift test
```

La suite verifica el vector HPKE, cifrado en ambas direcciones, rechazo de
replay, autenticación completa mediante un transporte simulado, envío cifrado y
rutas de error.
