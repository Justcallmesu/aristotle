package main

// Minimal RFC 6455 client (std lib only): text frames, ping/pong, close. No extensions are
// offered, so the server never compresses. Enough for the spike; not production code.

import (
	"bufio"
	"crypto/rand"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const (
	gatewayProtocol          = "hermes-gateway-v1"
	gatewayTicketPrefix      = "hermes-gateway-ticket."
	wsGUID                   = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
	opContinuation      byte = 0x0
	opText              byte = 0x1
	opBinary            byte = 0x2
	opClose             byte = 0x8
	opPing              byte = 0x9
	opPong              byte = 0xA
	maxMessageBytes          = 64 << 20
)

type wsConn struct {
	conn    net.Conn
	reader  *bufio.Reader
	writeMu sync.Mutex
}

// dialGateway upgrades <api_origin>/api/ws with the two subprotocols and NO Origin header
// (gateway/run_api.py:428-447: an Origin header sends the socket down the dashboard path).
func dialGateway(apiOrigin, ticket string) (*wsConn, *http.Response, error) {
	return dialGatewayWithOrigin(apiOrigin, ticket, "")
}

// dialGatewayWithOrigin exists only for the probe: a real client never sends Origin.
func dialGatewayWithOrigin(apiOrigin, ticket, originHeader string) (*wsConn, *http.Response, error) {
	origin, err := url.Parse(apiOrigin)
	if err != nil || origin.Scheme != "http" {
		return nil, nil, fmt.Errorf("unsupported api_origin %q", apiOrigin)
	}
	conn, err := net.DialTimeout("tcp", origin.Host, 10*time.Second)
	if err != nil {
		return nil, nil, err
	}
	keyBytes := make([]byte, 16)
	_, _ = rand.Read(keyBytes)
	key := base64.StdEncoding.EncodeToString(keyBytes)
	extra := ""
	if originHeader != "" {
		extra = "Origin: " + originHeader + "\r\n"
	}
	request := "GET /api/ws HTTP/1.1\r\n" +
		"Host: " + origin.Host + "\r\n" + extra +
		"Upgrade: websocket\r\nConnection: Upgrade\r\n" +
		"Sec-WebSocket-Key: " + key + "\r\n" +
		"Sec-WebSocket-Version: 13\r\n" +
		"Sec-WebSocket-Protocol: " + gatewayProtocol + ", " + gatewayTicketPrefix + ticket + "\r\n\r\n"
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
	if _, err := io.WriteString(conn, request); err != nil {
		conn.Close()
		return nil, nil, err
	}
	reader := bufio.NewReaderSize(conn, 64*1024)
	resp, err := http.ReadResponse(reader, &http.Request{Method: http.MethodGet})
	if err != nil {
		conn.Close()
		return nil, nil, fmt.Errorf("read upgrade answer: %w", err)
	}
	if resp.StatusCode != http.StatusSwitchingProtocols {
		conn.Close()
		return nil, resp, fmt.Errorf("upgrade refused: HTTP %d", resp.StatusCode)
	}
	sum := sha1.Sum([]byte(key + wsGUID))
	if resp.Header.Get("Sec-WebSocket-Accept") != base64.StdEncoding.EncodeToString(sum[:]) {
		conn.Close()
		return nil, resp, errors.New("bad Sec-WebSocket-Accept")
	}
	if got := resp.Header.Get("Sec-WebSocket-Protocol"); got != gatewayProtocol {
		conn.Close()
		return nil, resp, fmt.Errorf("subprotocol mismatch: %q", got)
	}
	_ = conn.SetDeadline(time.Time{})
	return &wsConn{conn: conn, reader: reader}, resp, nil
}

func (c *wsConn) writeFrame(opcode byte, payload []byte) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	header := []byte{0x80 | opcode}
	n := len(payload)
	switch {
	case n < 126:
		header = append(header, 0x80|byte(n))
	case n <= 0xFFFF:
		header = append(header, 0x80|126, byte(n>>8), byte(n))
	default:
		ext := make([]byte, 8)
		binary.BigEndian.PutUint64(ext, uint64(n))
		header = append(append(header, 0x80|127), ext...)
	}
	mask := make([]byte, 4)
	_, _ = rand.Read(mask)
	header = append(header, mask...)
	masked := make([]byte, n)
	for i := range payload {
		masked[i] = payload[i] ^ mask[i%4]
	}
	_ = c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	_, err := c.conn.Write(append(header, masked...))
	return err
}

func (c *wsConn) WriteText(text []byte) error { return c.writeFrame(opText, text) }

// ReadMessage returns the next complete text/binary message; it answers pings itself.
func (c *wsConn) ReadMessage() ([]byte, error) {
	var message []byte
	for {
		fin, opcode, payload, err := c.readFrame()
		if err != nil {
			return nil, err
		}
		switch opcode {
		case opPing:
			_ = c.writeFrame(opPong, payload)
			continue
		case opPong:
			continue
		case opClose:
			code := 0
			if len(payload) >= 2 {
				code = int(binary.BigEndian.Uint16(payload))
			}
			_ = c.writeFrame(opClose, payload)
			return nil, fmt.Errorf("server closed: code=%d reason=%q", code, strings.TrimSpace(string(payload[min(2, len(payload)):])))
		case opText, opBinary, opContinuation:
			message = append(message, payload...)
			if len(message) > maxMessageBytes {
				return nil, errors.New("message too large")
			}
			if fin {
				return message, nil
			}
		}
	}
}

func (c *wsConn) readFrame() (bool, byte, []byte, error) {
	head := make([]byte, 2)
	if _, err := io.ReadFull(c.reader, head); err != nil {
		return false, 0, nil, err
	}
	fin, opcode := head[0]&0x80 != 0, head[0]&0x0F
	length := uint64(head[1] & 0x7F)
	switch length {
	case 126:
		ext := make([]byte, 2)
		if _, err := io.ReadFull(c.reader, ext); err != nil {
			return false, 0, nil, err
		}
		length = uint64(binary.BigEndian.Uint16(ext))
	case 127:
		ext := make([]byte, 8)
		if _, err := io.ReadFull(c.reader, ext); err != nil {
			return false, 0, nil, err
		}
		length = binary.BigEndian.Uint64(ext)
	}
	if length > maxMessageBytes {
		return false, 0, nil, errors.New("frame too large")
	}
	var mask []byte
	if head[1]&0x80 != 0 {
		mask = make([]byte, 4)
		if _, err := io.ReadFull(c.reader, mask); err != nil {
			return false, 0, nil, err
		}
	}
	payload := make([]byte, length)
	if _, err := io.ReadFull(c.reader, payload); err != nil {
		return false, 0, nil, err
	}
	if mask != nil {
		for i := range payload {
			payload[i] ^= mask[i%4]
		}
	}
	return fin, opcode, payload, nil
}

func (c *wsConn) Close() {
	_ = c.writeFrame(opClose, []byte{0x03, 0xE8})
	_ = c.conn.Close()
}
