import { FaInstagram } from 'react-icons/fa';
import { FaMapMarkerAlt } from 'react-icons/fa';
import imgBackground from './assets/images/background.jpg';
import imgExpressManicure from './assets/images/Express_Manicure.jpg';
import imgClassicManicure from './assets/images/Classic_Manicure.jpg';
import imgStructuredClassic from './assets/images/Structured_Classic_Manicure.jpg';
import imgApresGelX from './assets/images/Apres_Extension.jpg';
import logoBlack from './assets/images/E1LN_Logo/E1LN_Black_Long_Logo.png';
import logoWhite from './assets/images/E1LN_Logo/E1LN_White_Long_Logo.png';
import { NAIL_ART, NAIL_SERVICES, REMOVAL } from './services';

const serviceImages = {
  express: imgExpressManicure,
  classic: imgClassicManicure,
  structured: imgStructuredClassic,
  apres: imgApresGelX,
};

function ValueIcon({ children }) {
  return (
    <svg
      fill="none"
      height="24"
      stroke="currentColor"
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth="1.8"
      viewBox="0 0 24 24"
      width="24"
      xmlns="http://www.w3.org/2000/svg"
    >
      {children}
    </svg>
  );
}

function HeartIcon() {
  return (
    <ValueIcon>
      <path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z" />
    </ValueIcon>
  );
}

function StarIcon() {
  return (
    <ValueIcon>
      <path d="M12 3l1.9 5.8a2 2 0 0 0 1.3 1.3L21 12l-5.8 1.9a2 2 0 0 0-1.3 1.3L12 21l-1.9-5.8a2 2 0 0 0-1.3-1.3L3 12l5.8-1.9a2 2 0 0 0 1.3-1.3L12 3Z" />
    </ValueIcon>
  );
}

function ShieldIcon() {
  return (
    <ValueIcon>
      <path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1Z" />
    </ValueIcon>
  );
}

function WaypointIcon() {
  return (
    <ValueIcon>
      <path d="M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0" />
      <circle cx="12" cy="10" r="3" />
    </ValueIcon>
  );
}

function LandingView({
  user,
  authModal,
  bookingModalEl,
  scrollToSection,
  onProfileClick,
  onSignIn,
  onBook,
  galleryImages,
  galleryIndex,
  onPrevSlide,
  onNextSlide,
  onSelectSlide,
}) {
  return (
    <div className="landing">
      {authModal}
      {bookingModalEl}
      {/* ── Navigation ── */}
      <nav className="landing-nav">
        <button className="landing-logo" onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })} type="button">
          <img alt="Every1 Luvs Nails" className="logo-nav" src={logoBlack} />
        </button>
        <div className="landing-nav-links">
          <button className="nav-text-link" onClick={() => scrollToSection('services')} type="button">Services</button>
          <button className="nav-text-link" onClick={() => scrollToSection('about')} type="button">About</button>
          <button className="nav-text-link" onClick={() => scrollToSection('contact')} type="button">Contact</button>
          {user ? (
            <button
              className="nav-tab-link"
              onClick={onProfileClick}
              type="button"
            >
              My Profile
            </button>
          ) : (
            <button
              className="nav-tab-link"
              onClick={onSignIn}
              type="button"
            >
              Sign In
            </button>
          )}
        </div>
        <button className="pill-button" onClick={onBook} type="button">Book Now</button>
      </nav>

      {/* ── Hero ── */}
      <section className="hero-section">
        <div className="hero-content">
          <div className="hero-pills">
            <span className="hero-pill">💅 Nail Studio</span>
            <span className="hero-pill">&#10022; CoolSculpting Fat Freeze Studio</span>
            <span className="hero-pill"><FaMapMarkerAlt /> Toa Payoh, Singapore</span>
          </div>
          <img alt="Every1 Luvs Nails" className="logo-hero" src={logoBlack} />
          <p className="hero-tagline">Where every detail <em>is loved</em></p>
          <p className="hero-body">Premium nail care and CoolSculpting fat freeze treatments crafted for you. From everyday manicures to stunning extensions and body contouring — your story, your way.</p>
          <button className="pill-button hero-cta" onClick={onBook} type="button">Book an Appointment</button>
        </div>
        <img alt="" aria-hidden="true" className="hero-photo" src={imgBackground} />
      </section>

      {/* ── CoolSculpting ── */}
      <section className="cool-section">
        <div className="cool-content">
          <p className="section-eyebrow light">Body Studio &middot; Toa Payoh</p>
          <h2 className="section-heading light">CoolSculpting<br />Fat Freeze</h2>
          <p className="cool-body">Clinically-proven cryolipolysis treatments to target stubborn fat — safely, comfortably, and without downtime. Our CoolSculpting studio is situated in the heart of Toa Payoh, Singapore.</p>
          <div className="cool-offer-box">
            <p className="cool-offer-label">&#10022; Opening Offer — Limited Time</p>
            <p className="cool-offer-price">1 Session &middot; S$40</p>
            <p className="cool-offer-note">This is a special opening offer and is available for a limited time only. Up to 10 sessions per customer. Regular pricing applies thereafter.</p>
          </div>
          <div className="cool-feature-pills">
            <span className="cool-feature-pill">No surgery</span>
            <span className="cool-feature-pill">No downtime</span>
            <span className="cool-feature-pill">FDA-cleared tech</span>
            <span className="cool-feature-pill">Results in 8–12 weeks</span>
          </div>
          {/* <p className="cool-instagram">&#64;every1luvs.co</p> */}
        </div>
        <div className="cool-photo-placeholder" aria-hidden="true" />
      </section>

      {/* ── Services ── */}
      <section className="services-section" id="services">
        <p className="section-eyebrow">Natural Nails &amp; Extensions</p>
        <h2 className="section-heading">Services Offered</h2>

        <div className="featured-service-card">
          <div className="featured-service-left">
            <div className="featured-badge-row">
              <span className="featured-badge">Limited Slots Monthly</span>
              <span className="featured-badge">Discounted</span>
            </div>
            <h3 className="featured-service-title">On My Mind, On Your Nails</h3>
            <p className="featured-service-desc">Discounted nail art sets — designs, vibes, and moodboards we're dying to create. Nail art will be similar to our Inspo photos. Slots are posted on our IG story. Limited slots each month. Once booked, considered gone.</p>
          </div>
          <button className="text-link-button featured-service-cta" onClick={onBook} type="button">Request a Slot &#8594;</button>
        </div>

        <div className="service-card-grid">
          {NAIL_SERVICES.map((svc) => (
            <div className="service-card" key={svc.id}>
              <div className="service-card-image-wrapper">
                <img alt={svc.name} className="service-card-photo" src={serviceImages[svc.id]} />
                {svc.popular && <span className="popular-badge">Popular</span>}
              </div>
              <div className="service-card-body">
                <p className="service-card-label">{svc.label}</p>
                <h3 className="service-card-title">{svc.name}</h3>
                <p className="service-card-desc">{svc.desc}</p>
                <div className="service-price-row">
                  <div className="price-box price-box--junior">
                    <span className="price-box-label">Junior</span>
                    <span className="price-box-value">S${svc.junior}</span>
                  </div>
                  <div className="price-box price-box--senior">
                    <span className="price-box-label">Senior</span>
                    <span className="price-box-value">S${svc.senior}</span>
                  </div>
                </div>
                <div className="service-card-footer">
                  <span className="service-duration-badge">&#128336; {svc.duration}</span>
                  <button className="service-book-btn" onClick={onBook} type="button">Book</button>
                </div>
              </div>
            </div>
          ))}
        </div>

        <div className="addons-section">
          <h3 className="addons-heading">Add-ons &amp; Repairs</h3>
          <div className="addons-grid">
            <div>
              <p className="addons-col-label">Nail Art Design</p>
              {NAIL_ART.filter((a) => a.id !== 'none').map((a) => (
                <div className="addon-row" key={a.id}>
                  <div>
                    <p className="addon-tier">{a.name}</p>
                    <p className="addon-desc">{a.sub}</p>
                  </div>
                  <span className="addon-price">from S${a.price}</span>
                </div>
              ))}
            </div>
            <div>
              <p className="addons-col-label">Removal</p>
              {REMOVAL.filter((r) => r.id !== 'none').map((r) => (
                <div className="addon-row" key={r.id}>
                  <div>
                    <p className="addon-tier">{r.name}</p>
                    <p className="addon-desc">{r.sub}</p>
                  </div>
                  <span className="addon-price">+S${r.price}</span>
                </div>
              ))}
              <p className="addons-col-label" style={{ marginTop: '24px' }}>Nail Repairs</p>
              {[
                { name: 'Nail Fix', price: 'S$3/nail', desc: 'Simple repair — nail back to a normal, flawless finish.' },
                { name: 'Single Nail Extension', price: 'S$8/nail', desc: 'Adds length on a broken or short nail for a seamless, balanced set.' },
              ].map((r) => (
                <div className="addon-row" key={r.name}>
                  <div>
                    <p className="addon-tier">{r.name}</p>
                    <p className="addon-desc">{r.desc}</p>
                  </div>
                  <span className="addon-price">{r.price}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </section>

      {/* ── Gallery ── */}
      <section className="gallery-section">
        <p className="section-eyebrow">Studio Work</p>
        <h2 className="section-heading">Our Nails, <em>Your Story</em></h2>
        <div className="gallery-slideshow">
          <button className="gallery-arrow gallery-arrow--prev" onClick={onPrevSlide} type="button" aria-label="Previous image">&#8249;</button>
          <div className="gallery-stage">
            {galleryImages.map((src, i) => (
              <img
                alt=""
                aria-hidden={i !== galleryIndex}
                className={`gallery-slide${i === galleryIndex ? ' gallery-slide--active' : ''}`}
                key={i}
                src={src}
              />
            ))}
          </div>
          <button className="gallery-arrow gallery-arrow--next" onClick={onNextSlide} type="button" aria-label="Next image">&#8250;</button>
        </div>
        <div className="gallery-dots">
          {galleryImages.map((_, i) => (
            <button
              aria-label={`Go to image ${i + 1}`}
              className={`gallery-dot${i === galleryIndex ? ' gallery-dot--active' : ''}`}
              key={i}
              onClick={() => onSelectSlide(i)}
              type="button"
            />
          ))}
        </div>
        <p className="gallery-instagram-prompt">Follow &#64;every1luvsnails for the latest sets</p>
      </section>

      {/* ── About ── */}
      <section className="about-section" id="about">
        <div className="about-content">
          <p className="section-eyebrow">Our Studio</p>
          <h2 className="section-heading">A space where <em>everyone is loved</em></h2>
          <p className="about-location"><WaypointIcon />Heart of Toa Payoh, Singapore</p>
          <p className="about-body">every1luvs is a premium nail and CoolSculpting studio built on a simple truth: great beauty services are for everyone. Whether it's a clean classic manicure or a full Après Gel X set with intricate art, we give every client the same care and attention to detail.</p>
          <p className="about-body">We use only premium gel systems and take pride in healthy nail practices — no damage, no shortcuts. Our CoolSculpting treatments use clinically-proven cryolipolysis technology.</p>
        </div>
        <div className="about-values">
          {[
            { Icon: HeartIcon, title: 'Inclusive by design', desc: 'Every nail type, every shape, every budget — we have a service for you.' },
            { Icon: StarIcon, title: 'Premium products only', desc: 'High-grade gel systems and Après Gel X extensions that last and protect your natural nails.' },
            { Icon: ShieldIcon, title: 'Healthy nail ethos', desc: 'We never rush. Proper prep, proper removal — your nail health is always the priority.' },
            { Icon: WaypointIcon, title: 'Conveniently located', desc: 'Based in the heart of Toa Payoh, Singapore. Easy access by MRT and bus.' },
          ].map((v) => (
            <div className="value-card" key={v.title}>
              <span className="value-icon"><v.Icon /></span>
              <h4>{v.title}</h4>
              <p className="value-card-desc">{v.desc}</p>
            </div>
          ))}
        </div>
      </section>

      {/* ── CTA Banner ── */}
      <section className="cta-banner">
        <p className="section-eyebrow" style={{ textAlign: 'center' }}>Ready?</p>
        <h2 className="cta-banner-heading">Book your next set</h2>
        <p className="cta-banner-sub">Secure your slot in under 2 minutes. A S$30 deposit is required to confirm.</p>
        <div className="cta-banner-buttons">
          <button className="outline-button" onClick={onBook} type="button">Book an Appointment</button>
          <button className="outline-button" type="button">Model Set Interest</button>
        </div>
      </section>

      {/* ── Footer ── */}
      <footer className="landing-footer" id="contact">
        <div className="footer-grid">
          <div className="footer-brand">
            <img alt="Every1 Luvs Nails" className="logo-footer" src={logoWhite} />
            <p className="footer-tagline">Premium nail &amp; CoolSculpting studio in Toa Payoh, Singapore.</p>
          </div>
          <div>
            <h4>Studio Hours</h4>
            <ul className="footer-hours-list">
              <li><span>Mon – Fri</span><span>10:30 – 19:00</span></li>
              <li><span>Saturday</span><span>10:30 – 19:00</span></li>
              <li><span>Sunday</span><span>Closed</span></li>
            </ul>
          </div>
          <div>
            <h4>Follow Us</h4>
            <ul className="footer-follow-list">
              <li>
                <a href="https://instagram.com/every1luvsnails" target="_blank" rel="noopener noreferrer">
                  <FaInstagram />
                  <span>&#64;every1luvsnails &middot; Nails</span>
                </a>
              </li>
              <li>
                <a href="https://instagram.com/every1luvs.co" target="_blank" rel="noopener noreferrer">
                  <FaInstagram />
                  <span>&#64;every1luvs.co &middot; CoolSculpting</span>
                </a>
              </li>
            </ul>
          </div>
          <div>
            <h4>Location</h4>
            <p className="footer-location-text">Toa Payoh, Singapore</p>
            <button className="pill-button footer-book-btn" onClick={onBook} type="button">Book Now</button>
          </div>
        </div>
        <div className="footer-bottom">
          <span>&#169; 2026 every1luvs. All rights reserved.</span>
          <span>Bookings via DM or this website.</span>
        </div>
      </footer>
    </div>
  );
}

export default LandingView;
