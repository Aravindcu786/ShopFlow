
import bcrypt

password = "12345678"

hashed = bcrypt.hashpw(
    password.encode("utf-8"),
    bcrypt.gensalt(rounds=12)
)

print(hashed.decode())
