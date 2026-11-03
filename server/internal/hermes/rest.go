package hermes

import (
	"context"
	"net/http"
	"net/url"
)

// Do sends req to Hermes REST: it re-identifies, rewrites the request to api_origin, mints a
// single-use native-http ticket for this one request and sets the ticket header. Any Origin header
// is dropped (Hermes refuses REST tickets with one). The caller closes the response body.
func (c *Connector) Do(ctx context.Context, req *http.Request) (*http.Response, error) {
	id, err := c.Identify(ctx)
	if err != nil {
		return nil, err
	}
	ticket, err := c.mintTicket(ctx, id, purposeNativeHTTP, "")
	if err != nil {
		return nil, err
	}
	origin, err := url.Parse(id.APIOrigin)
	if err != nil {
		return nil, err
	}
	out := req.Clone(ctx)
	out.URL.Scheme = origin.Scheme
	out.URL.Host = origin.Host
	out.Host = origin.Host
	out.RequestURI = ""
	out.Header.Del("Origin")
	out.Header.Set(ticketHTTPHeader, ticket)
	return c.client.Do(out)
}
