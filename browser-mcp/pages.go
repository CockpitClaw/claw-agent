package main

import "sync"

const browserPageIDStart = 1000

type managedPage struct {
	PageID int
	CDPID  string
	URL    string
	Title  string
}

type pageRegistry struct {
	mu    sync.Mutex
	pages []managedPage
	next  int
}

var registry = &pageRegistry{}

func (r *pageRegistry) refresh() ([]managedPage, error) {
	live, err := listPages()
	if err != nil {
		return nil, err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.pages = r.pages[:0]
	r.next = browserPageIDStart
	for _, p := range live {
		r.pages = append(r.pages, managedPage{
			PageID: r.next,
			CDPID:  p.ID,
			URL:    p.URL,
			Title:  p.Title,
		})
		r.next++
	}
	return r.pages, nil
}

func (r *pageRegistry) cached() []managedPage {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]managedPage(nil), r.pages...)
}

func (r *pageRegistry) resolveCDPID(pageID int) (string, error) {
	// Try cached first, but verify the cached CDPID is still live in Chrome's
	// /json listing. Chrome tabs get new target IDs after a close/reopen,
	// so a stale cached mapping produces "page not found: <hex targetId>"
	// downstream in findPageByID. Verifying costs one HTTP GET but only on the
	// cached-hit path; misses already refresh.
	if cdpID, ok := r.cachedCDPID(pageID); ok {
		if _, err := findPageByID(cdpID); err == nil {
			return cdpID, nil
		}
		// stale — fall through to refresh
	}

	if _, err := r.refresh(); err != nil {
		return "", err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, p := range r.pages {
		if p.PageID == pageID {
			return p.CDPID, nil
		}
	}
	return "", &toolError{Code: -32000, Message: "page not found: " + itoa(pageID)}
}

// cachedCDPID returns the CDPID for pageID if it's in the cache, without refreshing.
func (r *pageRegistry) cachedCDPID(pageID int) (string, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, p := range r.pages {
		if p.PageID == pageID {
			return p.CDPID, true
		}
	}
	return "", false
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := false
	if n < 0 {
		neg = true
		n = -n
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
