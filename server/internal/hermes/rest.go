package hermes

import (
	"context"
	"net/http"
	"net/url"
	"strings"
)

// Do sends req to Hermes REST as the given profile. profile is a served profile's name (the last
// element of its home under profiles/), its profile_id or its home path; "" or "default" is the launch
// profile. Hermes binds a REST request to the profile its ticket was minted for: implicit selectors
// resolve in the ticket's home and any other profile is 403 profile_scope_mismatch
// (hermes_cli/dashboard_auth/native_http.py:18-31,47-58,66-71,297-300), so the ticket is minted for
// exactly this profile.
//
// Do re-identifies, rewrites the request to api_origin, mints a single-use native-http ticket and sets
// the ticket header. Origin, Authorization, Cookie, Proxy-Authorization and every X-Hermes-* header the
// caller set are dropped. Redirects are not followed: the 3xx response is returned. The caller closes
// the response body; req.Body is closed on every path.
func (c *Connector) Do(ctx context.Context, profile string, req *http.Request) (*http.Response, error) {
	resp, err := c.do(ctx, profile, req)
	if err != nil && req.Body != nil {
		_ = req.Body.Close()
	}
	return resp, err
}

func (c *Connector) do(ctx context.Context, profile string, req *http.Request) (*http.Response, error) {
	id, err := c.Identify(ctx)
	if err != nil {
		return nil, err
	}
	profileID, err := id.resolveProfile(profile)
	if err != nil {
		return nil, err
	}
	ticket, err := c.mintTicket(ctx, id, profileID, purposeNativeHTTP, "")
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
	if out.Header == nil {
		out.Header = http.Header{}
	}
	for name := range out.Header {
		switch lower := strings.ToLower(name); {
		case lower == "origin", lower == "authorization", lower == "cookie",
			lower == "proxy-authorization", strings.HasPrefix(lower, "x-hermes-"):
			out.Header.Del(name)
		}
	}
	out.Header.Set(ticketHTTPHeader, ticket)
	return c.client.Do(out)
}
