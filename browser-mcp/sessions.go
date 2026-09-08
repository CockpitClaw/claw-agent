package main

import (
	"sync"
)

type sessionManager struct {
	mu       sync.Mutex
	sessions map[int]*cdpSession
}

var sessions = &sessionManager{sessions: make(map[int]*cdpSession)}

func (m *sessionManager) get(pageID int) (*cdpSession, error) {
	// Fast path: cached session with a live WS.
	m.mu.Lock()
	if s, ok := m.sessions[pageID]; ok && s.ws != nil {
		m.mu.Unlock()
		// Probe the connection with a trivial CDP call. If the WS is dead
		// (broken pipe / peer closed), drop the cached session and rebuild.
		if _, err := s.send("Page.getNavigationHistory", nil); err != nil {
			m.mu.Lock()
			delete(m.sessions, pageID)
			m.mu.Unlock()
			// fall through to rebuild
		} else {
			return s, nil
		}
	} else {
		m.mu.Unlock()
	}

	cdpID, err := registry.resolveCDPID(pageID)
	if err != nil {
		return nil, err
	}
	page, err := findPageByID(cdpID)
	if err != nil {
		return nil, err
	}
	s := newCDPSession(page.WebSocketDebuggerURL)
	if err := s.connect(page.WebSocketDebuggerURL); err != nil {
		return nil, err
	}
	m.mu.Lock()
	m.sessions[pageID] = s
	m.mu.Unlock()
	return s, nil
}

func (m *sessionManager) closeAll() {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, s := range m.sessions {
		s.close()
	}
	m.sessions = make(map[int]*cdpSession)
}
